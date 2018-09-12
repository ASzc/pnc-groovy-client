#!/usr/bin/env groovy

package ca.szc.groovy.pnc

import groovy.json.JsonSlurper
import groovy.transform.Memoized

import java.security.MessageDigest

class PncClient {

    private URL apiUrl
    private LinkedHashMap apiData

    /**
     * Prepare a client to operate against the PNC REST API, by preprocessing
     * the swagger data published by that API.
     *
     * @param apiRoot The common URL prefix of the REST API, including scheme,
     * host, and path (http://pnc.example.com/pnc-rest/rest). Should contain
     * swagger.json
     */
    PncClient(String apiRoot) {
        this.apiUrl = apiRoot.toURL()
        this.apiData = swaggerData(apiRoot + '/swagger.json')
    }

    /**
     * Call an API method with zero or more arguments
     *
     * @param group Category (group/tag) of the method
     * @param operation Operation identity of the method
     * @param kwargs Zero or more arguments for the method
     * @return Parsed json data, or null if the method returns nothing.
     */
    def exec(String group, String operation, Map kwargs=[:]) {
        def method = apiData['pathMethodsByTagAndId'][group][operation]

        println("${method['method']} ${method['path']} ${kwargs}")
        // Allocate kwargs to where the method data says they each need to go
        // TODO
    }

    //
    // Misc
    //

    private static String sha256(String s) {
        MessageDigest.getInstance("SHA-256").digest(s.bytes).encodeHex().toString()
    }

    private static String dashSeparated(String s) {
        s.replaceAll(/\B[A-Z]/) { '-' + it }.toLowerCase()
    }

    private static String wordWrap(text, length=80, start=0) {
        length = length - start

        def sb = new StringBuilder()
        def line = ''

        text.split(/\s/).each { word ->
            if (line.size() + word.size() > length) {
                sb.append(line.trim()).append('\n').append(' ' * start)
                line = ''
            }
            line += " $word"
        }
        sb.append(line.trim()).toString()
    }

    //
    // Model
    //

    static LinkedHashMap swaggerData(String swaggerUrl) {
        File cached = new File(System.getenv()['PGC_CACHE'] ?: (System.getProperty("user.home") + "/.cache/pgc-${sha256(swaggerUrl)}.json"))

        if (!cached.exists()) {
            cached.withOutputStream { output ->
                swaggerUrl.toURL().withInputStream { input ->
                    output << input
                }
            }
        }

        LinkedHashMap root = new JsonSlurper().parse(cached)

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
            //System.err.println("${key}  ${data}")
            //System.err.flush()
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
                        //System.err.println(">> ${content}")
                        //System.err.flush()
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
                        } else if (content['items']['enum']) {
                            // Enum singleton
                            refName = "enum"
                            refType = content['enum']
                            refMultiple = null
                        } else if (content['items']['$ref']) {
                            // Page
                            refName = extractRef(content['items']['$ref'])
                            refType = resolveRef(refName)
                            refMultiple = content['type']
                        } else {
                            throw new RuntimeException(
                                "Don't know how to handle data in ${key}"
                            )
                        }
                    } else {
                        refType = ref
                        refMultiple = null
                    }
                } else if (data['schema']['type']) {
                    if (data['schema']['items']) {
                        // Array
                        refName = extractRef(data['schema']['items']['$ref'])
                        refType = resolveRef(refName)
                        refMultiple = data['schema']['type']
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
                    if (!pathMethodsByTag.containsKey(tag)) {
                        pathMethodsByTag[tag] = []
                        pathMethodsByTagAndId[tag] = [:]
                    }
                    pathMethodsByTag[tag].add(methodData)

                    // Add in some ease-of-use data too
                    methodData['path'] = path
                    methodData['method'] = method
                    methodData['operationIdDashed'] = dashSeparated(methodData['operationId'])
                    if (!pathMethodsByTagAndId[tag].containsKey(methodData['operationId'])) {
                        // Add both camel case and dashed names to index
                        pathMethodsByTagAndId[tag][methodData['operationId']] = methodData
                        pathMethodsByTagAndId[tag][methodData['operationIdDashed']] = methodData
                    }

                    // Resolve $ref syntax for schemas
                    String debugKey = "${tag} ${methodData.operationId}"
                    methodData['parameters'].each { data ->
                        resolveType(data, "${debugKey} ${data.name}")
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

    static String formatListing(String swaggerUrl, String includePattern, Integer maxLength=80) {
        def root = swaggerData(swaggerUrl)

        def out = new StringBuilder()

        String tag
        String prevTag = null
        root['tags'].each { tagData ->
            tag = tagData['name']
            root['pathMethodsByTag'][tag].each { md ->
                String key = "${tag} ${md.operationIdDashed}"
                if (key =~ includePattern) {
                    // Add separator between different groups
                    if (prevTag != null && tag != prevTag) {
                        out << '===\n\n'
                    }
                    prevTag = tag

                    out << key
                    out << '\n'
                    md.parameters.each { data ->
                        // Pages are handled transparent to the user
                        if (! (data['name'] in ["pageIndex", "pageSize"])) {
                            //System.err.println("${name} ${type} ${description}")
                            //System.err.flush()
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
                            out << wordWrap(
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
        }

        return out.toString()
    }

    //
    // CLI
    //

    @Memoized
    private static Integer consoleWidth() {
        "tput cols".execute().text as Integer
    }

    @Memoized
    private static PrintWriter consoleWriter() {
        new PrintWriter(System.err)
    }

    private static OptionAccessor parse(cli, args, positionals=0) {
        cli.width = consoleWidth()
        cli.writer = consoleWriter()
        cli.expandArgumentFiles = false
        if (positionals != -1) { cli.stopAtNonOption = false }
        def options = cli.parse(args)
        if (!options) { return null }
        if (options.h) {
            cli.usage()
            return null
        }
        if (positionals != -1 && options.arguments().size() != positionals) {
            cli.writer.println("error: Incorrect number of positional arguments")
            cli.usage()
            return null
        }
        return options
    }

    private static CliBuilder commandCall() {
        def cli = new CliBuilder(
            usage:'pgc call [-a ARGUMENT=VALUE] group operation',
            header:"""
                   Use an endpoint of the server's REST API. Run the list subcommand to see what endpoints are available.

                   positional arguments:
                    group      The group name of the endpoint
                    operation  The operation name of the endpoint

                   optional arguments:
                   """.stripIndent(),
        )
        cli.with {
            h longOpt: 'help',
              'Show usage information'
            a longOpt: 'arg',
              args: 2,
              valueSeparator: '=',
              argName: 'ARGUMENT=VALUE',
              'Zero or more arguments for the endpoint Key=Value format. Value will be automatically coerced from a string to the appropriate type if needed by the endpoint.'
        }
        return cli
    }

    private static CliBuilder commandList() {
        def cli = new CliBuilder(
            usage:'pgc list [-e PATTERN]',
            header:"""
                   Print information on server API endpoints

                   positional arguments:

                   optional arguments:
                   """.stripIndent(),
        )
        cli.with {
            h longOpt: 'help',
              'Show usage information'
            e longOpt: 'regexp',
              args: 1,
              argName: 'PATTERN',
              'Show only endpoints with keys (group + operation) that contains this regex PATTERN'
        }
        return cli
    }

    private static CliBuilder commandRoot() {
        def cli = new CliBuilder(
            usage:'pgc [-d] {call,list}',
            header:"""
                   PNC Groovy Client, a CLI for the PNC REST API.

                   See the individual help messages of each subcommand for more information.

                   subcommand arguments:
                    {call,list}
                     call    Use server API endpoint
                     list    Print information on server API endpoints

                   optional arguments:
                   """.stripIndent(),
        )
        cli.with {
            h longOpt: 'help',
              'Show usage information'
            d longOpt: 'debug',
              'Enable debug logging'
        }
        return cli
    }

    private static Properties readConfig(File config=null) {
        config = config ?: new File(System.getenv()['PGC_CONFIG'] ?: (System.getProperty("user.home") + '/.config/pgc.properties'))
        Properties props = new Properties()
        if (config.exists()) {
            props.load(config.newDataInputStream())
        }
        return props
    }

    static Integer cli(args) {
        PrintWriter console = consoleWriter()
        try {
            def cli = commandRoot()
            def options = parse(cli, args, -1)
            if (!options) { return 1 }
            //console.println(">> ${options.arguments()}")

            def pncUrl
            def readConfig = { ->
                Properties config = readConfig()
                if (!config.getProperty('pnc.url')) {
                    console.println('error: Setting pnc.url is missing from the config file')
                    return 4
                }
                pncUrl = config.getProperty('pnc.url')
            }

            String subcommand = options.arguments()[0]
            def subargs = options.arguments().drop(1)
            switch (subcommand) {
                case 'call':
                    def suboptions = parse(commandCall(), subargs, 2)
                    if (!suboptions) { return 3 }

                    // Positional args -> method coordinates
                    def positionals = suboptions.arguments()
                    def group = positionals[0]
                    def operation = positionals[1]

                    // Optional args -> call keyword arguments
                    // Groovy 2.4.x's CliBuilder spits them out as a big list, can
                    // remove this if Fedora ever updates to 2.5+
                    def callArgs = [:]
                    def key = null
                    suboptions.as.each { a ->
                        if (key == null) {
                            key = a
                        } else {
                            callArgs[key] = a
                        }
                    }

                    readConfig()

                    new PncClient(pncUrl).exec(group, operation, callArgs)
                    break
                case 'list':
                    def suboptions = parse(commandList(), subargs)
                    if (!suboptions) { return 3 }

                    readConfig()

                    System.out.print(formatListing(
                        pncUrl + '/swagger.json',
                        suboptions.e ?: /.*/,
                        consoleWidth(),
                    ))
                    break
                default:
                    console.println("error: Unknown subcommand ${subcommand}")
                    cli.usage()
                    return 2
            }
        } finally {
            console.flush()
        }
        return 0
    }
}

//
// Main
//

System.exit(PncClient.cli(args))
