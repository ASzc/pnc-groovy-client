package ca.szc.groovy.pnc

import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import groovy.util.logging.Slf4j

import groovyx.net.http.ContentTypes
import groovyx.net.http.optional.Download
import groovyx.net.http.ApacheHttpBuilder
import groovyx.net.http.HttpBuilder

import org.apache.http.impl.client.HttpClientBuilder

@Slf4j
class PncClient {

    static HttpBuilder defaultHttp(httpCustomizer, api) {
        return ApacheHttpBuilder.configure {
            request.uri = api
            request.contentType = ContentTypes.JSON[0]
            if (httpCustomizer != null) {
                client.clientCustomizer(httpCustomizer)
            }
        }
    }

    private HttpBuilder http
    private LinkedHashMap apiData
    private Auth auth
    private File cacheDir

    /**
     * Prepare a client to operate against the PNC REST API, by preprocessing
     * the swagger data published by that API.
     *
     * @param api If a File, read the swagger data from there. Otherwise must
     * be the String URL to swagger.json within the REST API, including scheme,
     * host, and path. Example:
     * http://pnc.example.com/pnc-rest/rest/swagger.json
     * @param cache If null, disable caching of swagger data and authentication
     * tokens. If a File, use that directory as the cache directory.
     * @param auth If null, disable authentication. Otherwise, use this to
     * perform authentication if the API requests it.
     * @param httpCustomizer Closure to modify the http client implementation.
     * See org.apache.http.impl.client.HttpClientBuilder
     */
    PncClient(
        api,
        File cache=null,
        Auth auth=null,
        Closure<HttpClientBuilder> httpCustomizer=null
    ) {
        this.auth = auth
        this.cacheDir = cache

        LinkedHashMap root
        if (api instanceof File) {
            log.debug("Reading API data from file ${api}")
            root = new JsonSlurperClassic().parse(api)
        } else {
            this.http = defaultHttp(httpCustomizer, api)
            if (cache != null) {
                File swaggerCache = new File(cache, "${Misc.sha256(api)}.json")
                if (!swaggerCache.exists()) {
                    log.debug("Downloading API data to cache ${swaggerCache}")
                    cache.mkdirs()
                    this.http.get { Download.toFile(delegate, swaggerCache) }
                }
                log.debug("Reading API data from cache ${swaggerCache}")
                root = new JsonSlurperClassic().parse(swaggerCache)
            } else {
                log.debug("Downloading API data from ${api}")
                root = this.http.get { }
            }
        }

        this.apiData = swaggerData(root)
    }

    /**
     * Call an API method with zero or more arguments, returning any output as
     * a single piece once it has all been received from the API.
     *
     * @param group Category (group/tag) of the method
     * @param operation Operation identity of the method
     * @param kwargs Zero or more arguments for the method
     * @return Parsed json data, or null if the method returns nothing.
     */
    def exec(String group, String operation, Map kwargs=[:]) {
        def rawOutput = new StringWriter()
        execStream(group, operation, rawOutput, kwargs)
        return new JsonSlurperClassic().parseText(rawOutput.toString())
    }

    /**
     * Call an API method with zero or more arguments, returning any output
     * piecewise, as it is received from the API. Any pagination artifacts will
     * be stripped, so that the returned data is a single valid piece of json
     * text.
     *
     * @param group Category (group/tag) of the method
     * @param operation Operation identity of the method
     * @param output Where to stream the output to
     * @param kwargs Zero or more arguments for the method
     */
    void execStream(String group, String operation, Writer output, Map kwargs=[:]) {
        def method = apiData['pathMethodsByTagAndId'][group][operation]

        log.info("Calling ${method['method']} ${method['path']} with ${kwargs}")

        // Allocate kwargs to where the method data says they each need to go
        def pathParams = []
        def queryParams = [:]
        def bodyParams = [:]
        kwargs.each { k, v ->
            if (!(k in method['parametersByName'])) {
                throw new ModelCoerceException("Can't add parameter ${k} to execution, it isn't expected")
            }
            def parameter = method['parametersByName'][k]
            switch (parameter['in']) {
                case 'path':
                    pathParams.add(parameter)
                    break
                case 'query':
                    queryParams[k] = v
                    break
                case 'body':
                    bodyParams[k] = v
                    break
                default:
                    throw new ModelCoerceException("Unknown target ${parameter['in']} for parameter ${k}")
            }
        }

        // Detect pagination
        def paginated = false
        if ('pageIndex' in method['parametersByName']) {
            log.debug("Operation is paginated")
            paginated = true
            assert method['parametersByName']['pageIndex']['in'] == 'query'
            queryParams['pageIndex'] = 0
            queryParams['pageSize'] = 100
        }

        // Path
        def pathParts = method['path'].tokenize('/')
        pathParams.each { parameter ->
            pathParts = pathParts.collect { part ->
                if (part == "{${parameter['name']}}") {
                    return kwargs[parameter['name']]
                } else {
                    return part
                }
            }
        }
        def path = "${apiData['basePath']}/${pathParts.join('/')}"

        // TODO Not implemented
        assert !bodyParams

        // Output helper
        def jsonOut = { data ->
            JsonOutput.prettyPrint(JsonOutput.toJson(data))
        }

        // Make at least one HTTP request.
        // More than one may be required in any combination of these cases:
        //   - API requests authentication
        //   - API returns paginated data
        def stuffWritten = false
        def pagesPending = false
        def pageStitch = false
        def serverErrors = 0
        def authErrors = 0
        def makeRequests = {
            def authNeeded = false
            def retry = false
            def failure = null
            def resp = http."${method['method']}" {
                request.uri.path = path
                request.uri.query = queryParams

                if (method['consumes'] && !method['consumes'].contains('application/json')) {
                    def consumes = method['consumes'].find { it != 'application/json' }
                    log.debug("Non-standard request content type ${method['consumes']}")
                    request.contentType = consumes
                }

                if (auth != null && auth.accessToken != null) {
                    if (authErrors <= 1) {
                        log.debug("Trying request with authentication")
                        request.headers['Authorization'] = "Bearer ${auth.accessToken}"
                    } else {
                        // If the first token refresh failed, try without an
                        // Authorization header, as the API may be rejecting
                        // old credentials without needing to.
                        log.warn("Reauth unsuccessful, retrying without authentication")
                    }
                } else {
                    if (auth == null) {
                        log.debug("Trying request without authentication, null access token")
                    } else {
                        log.debug("Trying request without authentication, auth disabled")
                    }
                }

                response.when(401) { fromServer, body ->
                    log.info("Authentication required")
                    authNeeded = true
                    authErrors++
                    if (authErrors > 2) {
                        log.error("Giving up on authenticating")
                        failure = fromServer.statusCode
                    }
                    return body
                }

                response.failure { fromServer, body ->
                    // Retry on server errors
                    if (fromServer.statusCode.intdiv(100) == 5) {
                        serverErrors++
                        // Give up after several attempts
                        if (serverErrors > 4) {
                            log.error("Server failure, giving up")
                            failure = fromServer.statusCode
                        } else {
                            log.info("Server failure, retrying")
                            retry = true
                        }
                    } else {
                        failure = fromServer.statusCode
                    }
                    return body
                }
            }
            if (failure) {
                if (resp instanceof Map && 'errorMessage' in resp) {
                    throw new ServerException(resp['errorMessage'])
                }
                throw new ServerException("HTTP error ${failure}")
            }
            if (authNeeded) {
                if (auth == null) {
                    throw new AuthException("Authentication is required but" +
                    "unavailable. Try running pgc login?")
                }
                if (authErrors <= 1) {
                    auth.refresh(cacheDir)
                }
                return true
            }
            if (retry) {
                sleep(10 ** serverErrors)
                return true
            } else {
                serverErrors = 0
            }

            def json
            if (resp == null) {
                log.debug("Got null")
                throw new ServerException("Server returned null")
            } else if (paginated) {
                log.debug("Got data page")
                pagesPending = (resp['totalPages'] - 1) > resp['pageIndex']
                json = jsonOut(resp['content'])

                // Strip characters so the pages can be stitched together
                if (pagesPending) {
                    if (pageStitch) {
                        log.debug("Middle page")
                        // Middle page, remove array open and close
                        json = json[1..-3] + ',\n'
                    } else {
                        // First page, remove array close
                        log.debug("First page")
                        json = json[0..-3] + ',\n'
                        pageStitch = true
                    }
                    queryParams['pageIndex'] = resp['pageIndex'] + 1
                } else if (pageStitch) {
                    // Last page, remove array open
                    log.debug("Last page")
                    json = json[1..-1]
                    pageStitch = false
                }
            } else if (resp.size() == 1 && resp['content']) {
                log.debug("Got nested schema")
                // Schemas nested in an empty object
                json = jsonOut(resp['content'])
            } else {
                log.debug("Got data")
                json = jsonOut(resp)
                pagesPending = false
            }
            stuffWritten = true
            output.write(json)
            output.flush()
            return pagesPending
        }
        while (makeRequests()) continue
        log.debug("Done making requests")
        if (stuffWritten) {
            output.write('\n')
            output.flush()
        }
    }

    //
    // Model
    //

    static LinkedHashMap swaggerData(LinkedHashMap root) {
        log.debug("Processing swagger data")
        // Local helper functions
        def extractRef = { String ref ->
            ref[ref.lastIndexOf('/') + 1..-1]
        }
        def resolveRef = { String refName ->
            def ref = root['definitions'][refName]
            ref['name'] = refName
            return ref
        }
        def resolveType = { data, key ->
            log.debug("Type resolve: ${key} ${data}")
            String refName
            String refType
            String refMultiple
            if (data['type']) {
                // Primitive
                refName = data['type']
                refType = null
                refMultiple = null
            } else if (data['schema']) {
                // Schema parameter type
                if (data['schema']['$ref']) {
                    refName = extractRef(data['schema']['$ref'])
                    def ref = resolveRef(refName)
                    // Expand nested schemas
                    if (ref['properties']['content']) {
                        def content = ref['properties']['content']
                        log.debug("Nested schema")
                        if (content['$ref']) {
                            // Singleton
                            refName = extractRef(content['$ref'])
                            refType = resolveRef(refName)
                            refMultiple = null
                        } else if (content['type'] && content['format']) {
                            // Primitive singleton
                            refName = content['type']
                            refType = null
                            refMultiple = null
                        } else if (content['additionalProperties']) {
                            // Primitive singleton
                            refName = content['additionalProperties']['type']
                            refType = null
                            refMultiple = null
                        } else if (content['type'] == 'object' && content.size() == 1) {
                            // Non-descript object
                            refName = content['type']
                            refType = null
                            refMultiple = null
                        } else if (content['items'] && content['items']['enum']) {
                            // Enum singleton
                            refName = "enum"
                            refType = content['enum']
                            refMultiple = null
                        } else if (content['items'] && content['items']['$ref']) {
                            // Page
                            refName = extractRef(content['items']['$ref'])
                            refType = resolveRef(refName)
                            refMultiple = content['type']
                        } else {
                            throw new RuntimeException(
                                "Don't know how to handle nested schema data in ${key}"
                            )
                        }
                    } else {
                        refType = ref
                        refMultiple = null
                    }
                } else if (data['schema']['type']) {
                    if (data['schema']['items']) {
                        // Array
                        if (data['schema']['items']['$ref']) {
                            refName = extractRef(data['schema']['items']['$ref'])
                            refType = resolveRef(refName)
                            refMultiple = data['schema']['type']
                        } else if (data['schema']['items']['type'] == 'object') {
                            // Non-descript object
                            refName = data['schema']['items']['type']
                            refType = null
                            refMultiple = null
                        } else {
                            // Primitive
                            refName = data['schema']['type']
                            refType = null
                            refMultiple = null
                        }
                    } else {
                        // Primitive
                        refName = data['schema']['type']
                        refType = null
                        refMultiple = null
                    }
                } else {
                    throw new RuntimeException(
                        "Don't know how to handle data in ${key}"
                    )
                }
            } else {
                // Nothing
                refName = null
                refType = null
                refMultiple = null
            }
            data['type'] = refName
            data['typeRef'] = refType
            data['typeMultiple'] = refMultiple
        }

        // Calculate some indexes
        def pathMethodsByTagAndId = [:]
        root['pathMethodsByTagAndId'] = pathMethodsByTagAndId
        def pathMethodsByTag = [:]
        root['pathMethodsByTag'] = pathMethodsByTag
        root['paths'].each { path, pathData ->
            pathData.each { method, methodData ->
                methodData['tags'].each { tag ->
                    def tagCamel = Misc.camelCase(tag)
                    if (!pathMethodsByTag.containsKey(tag)) {
                        pathMethodsByTag[tag] = []
                        pathMethodsByTagAndId[tag] = [:]
                        pathMethodsByTagAndId[tagCamel] = [:]
                    }
                    pathMethodsByTag[tag].add(methodData)

                    // Add in some ease-of-use data too
                    methodData['path'] = path
                    methodData['method'] = method
                    methodData['operationIdDashed'] = Misc.dashSeparated(methodData['operationId'])
                    if (!pathMethodsByTagAndId[tag].containsKey(methodData['operationId'])) {
                        // Add both camel case and dashed names to index
                        pathMethodsByTagAndId[tag][methodData['operationId']] = methodData
                        pathMethodsByTagAndId[tag][methodData['operationIdDashed']] = methodData
                        // And add a camel case version of the tag with the same pointers
                        pathMethodsByTagAndId[tagCamel][methodData['operationId']] = methodData
                        pathMethodsByTagAndId[tagCamel][methodData['operationIdDashed']] = methodData
                    }

                    // Resolve $ref syntax for schemas
                    def parametersByName = [:]
                    methodData['parametersByName'] = parametersByName
                    String debugKey = "${tag} ${methodData.operationId}"
                    methodData['parameters'].each { data ->
                        resolveType(data, "${debugKey} ${data.name}")
                        parametersByName[data['name']] = data
                    }
                    methodData['responses'].each { status, data ->
                        resolveType(data, "${debugKey} ${data.description}")
                        // Add in some more ease-of-use data
                        data['status'] = status
                    }
                }
            }
        }

        return root
    }

    //
    // List Command
    //

    String formatListing(String includePattern, Integer maxLength=80, Integer verbosity=0) {
        def root = apiData

        def out = new StringBuilder()

        String tag
        String prevTag = null
        root['tags'].each { tagData ->
            tag = tagData['name']
            root['pathMethodsByTag'][tag].each { md ->
                String key = "${tag} ${md.operationIdDashed}"
                if (!(key =~ includePattern)) {
                    return
                }

                // Add separator between different groups
                if (verbosity >= 1 && prevTag != null && tag != prevTag) {
                    out << '===\n\n'
                }
                prevTag = tag

                out << key
                out << '\n'

                if (verbosity <= 0) {
                    return
                }

                md.parameters.each { data ->
                    // Pages are handled transparent to the user
                    if (! (data['name'] in ["pageIndex", "pageSize"])) {
                        Integer start = out.length()
                        out << '  '
                        if (data['required']) {
                            out << '=> '
                        } else {
                            out << '-> '
                        }
                        out << data['name']
                        out << ' ('
                        out << data['type']
                        if (data['typeMultiple'] == 'array') {
                            out << '+'
                        }
                        out << ') '
                        out << Misc.wordWrap(
                            data['description'] ?: 'No description provided',
                            maxLength,
                            // Measure the length of the initial line info so we
                            // know where to wrap to.
                            out.length() - start,
                        )
                        out << '\n'
                    }
                }
                md.responses.any { status, data ->
                    // Success return type seems to be the only intersting one
                    if (status == 'default' || Integer.valueOf(status).intdiv(100) == 2) {
                        // Do not print return data for methods that don't
                        // return anything.
                        if (data['type'] != null) {
                            out << '  <- '
                            out << data['type']
                            if (data['typeMultiple'] == 'array') {
                                out << '+'
                            }
                            out << '\n'
                            return true
                        }
                    }
                }
                out << '\n'
            }
        }

        return out.toString()
    }
}
