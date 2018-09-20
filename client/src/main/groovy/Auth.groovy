package ca.szc.groovy.pnc

import groovyx.net.http.ContentTypes
import groovyx.net.http.HttpBuilder
import groovyx.net.http.NativeHandlers

class Auth {

    //
    // Misc
    //

    static final USER_CLIENT_ID = 'pncdirect'
    static enum Grant {
        CLIENT, USER
    }

    static HttpBuilder defaultHttp(http) {
        return http ?: HttpBuilder.configure {
            request.contentType = ContentTypes.JSON[0]
        }
    }

    //
    // Serialize
    //

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

    static final INFO_FILENAME = 'pgc-tokens.ser'

    static void infoOut(File file, AuthInfo info) {
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

    static AuthInfo infoIn(File file) {
        def info
        file.withObjectInputStream { i ->
            info = i.readObject()
        }
        return info
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
        HttpBuilder http=null
    ) {
        http = Auth.defaultHttp(http)

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

        url = "${url}/auth/realms/${realm}/protocol/openid-connect/token"

        def failure = false
        def resp = http.post {
            request.uri.full = url

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

    static void store(
        String url,
        String realm,
        Auth.Grant grant,
        String name,
        String secret,
        File cache
    ) {
        def auth = Auth.initial(url, realm, grant, name, secret)
        Auth.infoOut(
            new File(cache, INFO_FILENAME),
            auth.info,
        )
    }

    static Auth retrieve(File cache) {
        def authFile = new File(cache, INFO_FILENAME)
        if (authFile.exists()) {
            new Auth(
                Auth.infoIn(
                    new File(cache, INFO_FILENAME),
                ),
            )
        } else {
            return null
        }
    }

    //
    // Instance
    //

    AuthInfo info
    HttpBuilder http

    private Auth(info, http=null) {
        this.info = info
        this.http = Auth.defaultHttp(http)
    }

    void refresh() {
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
    }

    String getAccessToken() {
        this.info.accessToken
    }
}
