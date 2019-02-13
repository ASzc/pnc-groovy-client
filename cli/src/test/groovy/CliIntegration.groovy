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

    def "list returns data"() {
        when:
            def (code, err, out) = cli('list')
        then:
            code == 0
            err.length() == 0
            out.length() > 200
    }

    def "simple query returns data"() {
        when:
            def (code, err, out) = cli('call', 'build-records', 'get-specific', '-a', 'id=1')
            def start = out.take(100)
        then:
            code == 0
            err.length() == 0
            out.length() != 0
            isValidJson(out)
            start.startsWith('{\n    "id": 1,\n')
    }

    def "paged query returns data"() {
        when:
            def (code, err, out) = cli('call', 'build-records', 'get-built-artifacts', '-a', 'id=7113')
            def start = out.take(100)
            def end = out.reverse().take(100).reverse()
        then:
            code == 0
            err.length() == 0
            out.length() != 0
            isValidJson(out)
            start.startsWith('[\n    {\n        "id": ')
            end.endsWith('    }\n]\n')
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
