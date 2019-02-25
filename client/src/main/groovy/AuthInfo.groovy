package ca.szc.groovy.pnc

import groovy.transform.EqualsAndHashCode

@EqualsAndHashCode
class AuthInfo implements Serializable {
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
