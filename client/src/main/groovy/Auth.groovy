package ca.szc.groovy.pnc

import groovy.transform.EqualsAndHashCode
import groovy.util.logging.Slf4j

import groovyx.net.http.ContentTypes
import groovyx.net.http.ApacheHttpBuilder
import groovyx.net.http.HttpBuilder
import groovyx.net.http.NativeHandlers

import org.apache.http.impl.client.HttpClientBuilder

@Slf4j
class Auth {

    //
    // Misc
    //

    static final USER_CLIENT_ID = 'pncdirect'
    static enum Grant {
        CLIENT, USER
    }

    static HttpBuilder defaultHttp(httpCustomizer) {
        return ApacheHttpBuilder.configure {
            request.contentType = ContentTypes.JSON[0]
            if (httpCustomizer != null) {
                client.clientCustomizer(httpCustomizer)
            }
        }
    }

    //
    // Serialize
    //

    @EqualsAndHashCode
    static class AuthInfo implements Serializable {
        static final serialVersionUID = 7092186726994503341L

        final String url
        final String clientId
        final String refreshToken
        final String accessToken

        AuthInfo(url, clientId, refreshToken, accessToken) {
            assert url != null
            assert clientId != null
            assert refreshToken != null
            assert accessToken != null
            this.url = url
            this.clientId = clientId
            this.refreshToken = refreshToken
            this.accessToken = accessToken
        }
    }

    static String filename(String url) {
        "${Misc.sha256(url)}.ser"
    }

    static void infoOut(File dir, AuthInfo info) {
        dir.mkdirs()
        def file = new File(dir, filename(info.url))
        log.debug("Writing auth tokens to ${file}")
        // Set mode 600. Should really set the mode atomically at file creation
        // time, but Java makes that awkward to do.
        file.createNewFile()
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setReadable(false, false)
        file.setReadable(true, true)

        // Write data
        file.withObjectOutputStream { o ->
            o.writeObject(info)
        }

    }

    static AuthInfo infoIn(File dir, String url) {
        def file = new File(dir, filename(url))
        if (file.exists()) {
            log.debug("Reading auth tokens from ${file}")
            def info
            try {
                file.withObjectInputStream { i ->
                    info = i.readObject()
                }
                return info
            } catch (Exception e) {
                log.debug("Unable to read token cache, returning nothing. ${e}")
                return null
            }
        } else {
            log.debug("No auth tokens in cache ${dir} for url ${url}")
            return null
        }
    }

    //
    // Static Constructors
    //

    static Auth initial(
        String url,
        String realm,
        Auth.Grant grant,
        String name,
        String secret,
        Closure<HttpClientBuilder> httpCustomizer=null
    ) {
        log.info("Getting initial authentication tokens")
        def http = Auth.defaultHttp(httpCustomizer)

        def requestBody
        def clientId
        if (grant == Auth.Grant.USER) {
            requestBody = [
                client_id: Auth.USER_CLIENT_ID,
                grant_type: 'password',
                username: name,
                password: secret,
            ]
            clientId = Auth.USER_CLIENT_ID
        } else {
            requestBody = [
                client_id: name,
                grant_type: 'client_credentials',
                client_secret: secret,
            ]
            clientId = name
        }

        def failure = false
        def resp = http.post {
            request.uri.full = "${url}/auth/realms/${realm}/protocol/openid-connect/token"

            request.body = requestBody
            request.contentType = 'application/x-www-form-urlencoded'
            request.encoder 'application/x-www-form-urlencoded', NativeHandlers.Encoders.&form

            response.failure { fromServer, body ->
                failure = true
                return body
            }
        }
        if (failure) {
            throw new AuthException("${resp['error_description']}")
        }

        return new Auth(
            new AuthInfo(
                url,
                clientId,
                resp['refresh_token'],
                resp['access_token'],
            ),
            http,
        )
    }

    static Auth store(
        String url,
        String realm,
        Auth.Grant grant,
        String name,
        String secret,
        File cache,
        Closure<HttpClientBuilder> httpCustomizer=null
    ) {
        def auth = Auth.initial(url, realm, grant, name, secret, httpCustomizer)
        Auth.infoOut(cache, auth.info)
        return auth
    }

    static Auth retrieve(
        File cache,
        String url,
        Closure<HttpClientBuilder> httpCustomizer=null
    ) {
        if (!url) {
            return null
        }
        def info = Auth.infoIn(cache, url)
        if (!info) {
            return null
        }
        return new Auth(info, defaultHttp(httpCustomizer))
    }

    //
    // Instance
    //

    AuthInfo info
    HttpBuilder http

    private Auth(info, http=null) {
        this.info = info
        this.http = http
    }

    void refresh(File cache=null) {
        log.info("Getting new access token using refresh token")
        def failure = false
        def resp = http.post {
            request.uri.full = info.url

            request.body = [
                grant_type: 'refresh_token',
                client_id: info.clientId,
                refresh_token: info.refreshToken,
            ]
            request.contentType = 'application/x-www-form-urlencoded'
            request.encoder 'application/x-www-form-urlencoded', NativeHandlers.Encoders.&form

            response.failure { fromServer, body ->
                failure = true
                return body
            }
        }
        if (failure) {
            throw new AuthException("${resp}")
        }

        this.info = new AuthInfo(
            info.url,
            info.clientId,
            info.refreshToken,
            resp['access_token'],
        )
        if (cache) {
            Auth.infoOut(cache, this.info)
        }
    }

    String getAccessToken() {
        this.info.accessToken
    }
}
