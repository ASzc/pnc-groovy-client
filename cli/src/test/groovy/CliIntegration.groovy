package ca.szc.groovy.pnc

import groovy.json.JsonException
import groovy.json.JsonSlurper

import spock.lang.*

@IgnoreIf({ !properties['integration.pnc.host'] })
class CliIntegrationSpec extends Specification {

    def cli(String... args) {
        def err = new ByteArrayOutputStream()
        def out = new ByteArrayOutputStream()
        def code = PncCli.cli(
            args,
            err,
            out,
            [
                'pnc.url': "http://${System.getProperty('integration.pnc.host')}/pnc-rest/rest/swagger.json",
                cache: "/tmp/pgc-integration",
            ],
        )
        return [code, err.toString(), out.toString()]
    }

    def isValidJson(String s) {
        try {
            new JsonSlurper().parseText(s)
            return true
        } catch (JsonException e) {
            return false
        }
    }

    def "simple query returns data"() {
        when:
            def (code, err, out) = cli('call', 'build-records', 'get-specific', '-a', 'id=1')
        then:
            code == 0
            err.length() == 0
            out.length() != 0
            isValidJson(out)
            out.startsWith('{\n    "id": 1,\n')
    }

    def "paged query returns data"() {
        when:
            def (code, err, out) = cli('call', 'build-records', 'get-built-artifacts', '-a', 'id=1')
        then:
            code == 0
            err.length() == 0
            out.length() != 0
            isValidJson(out)
            out.startsWith('[\n    {\n        "id": 1,\n')
            out.endsWith('    }\n]\n')
    }

    def "non-authed write returns Auth error"() {
        when:
            def (code, err, out) = cli('call', 'build-tasks', 'cancel-bbuild', '-a', 'buildExecutionConfigurationId=1')
        then:
            code == 7
            err.length() != 0
            out.length() == 0
    }

}
