package ca.szc.groovy.pnc

import spock.lang.*

class ClientSpec extends Specification {

    def client() {
        new PncClient(
            new File('src/test/resources/swagger.json'),
        )
    }

    def "swagger data is parsed without error"() {
        when:
            client()
        then:
            notThrown(Throwable)
    }

    def "formatListing returns output"() {
        setup:
            def client = client()
        when:
            def listing = client.formatListing(/.*/)
        then:
            listing != null
            listing.size() > 200
    }

    def "verbose listing is longer than standard"() {
        setup:
            def client = client()
        when:
            def standard = client.formatListing(/.*/, 80)
            def verbose = client.formatListing(/.*/, 80, 1)
        then:
            standard.length() < verbose.length()
    }

    def "filtered listing is shorter than nonfiltered"() {
        setup:
            def client = client()
        when:
            def nonfiltered = client.formatListing(/.*/)
            def filtered = client.formatListing(/get-all/)
        then:
            nonfiltered.length() > filtered.length()
    }
}
