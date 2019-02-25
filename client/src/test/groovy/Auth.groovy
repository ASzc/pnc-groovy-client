package ca.szc.groovy.pnc

import spock.lang.*

class AuthSpec extends Specification {

    File dir = new File('/tmp/pgc-integration')

    def "auth tokens can be written and read"() {
        setup:
            def url = 'https://example.com'
            def writtenInfo = new AuthInfo(
                url,
                'some-client-id',
                'some-refresh-token',
                'some-access-token',
            )
        when:
            Auth.infoOut(dir, writtenInfo)
            def readInfo = Auth.infoIn(dir, url)
        then:
            readInfo == writtenInfo
    }
}
