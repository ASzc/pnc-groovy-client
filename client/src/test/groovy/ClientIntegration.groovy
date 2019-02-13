package ca.szc.groovy.pnc

import spock.lang.*

@IgnoreIf({ !properties['integration.pnc.host'] })
class ClientIntegrationSpec extends Specification {

    def client = new PncClient(
        "http://${System.getProperty('integration.pnc.host')}/pnc-rest/rest/swagger.json",
    )

    def "simple query returns data"() {
        when:
            def out = client.exec('buildRecords', 'getSpecific', [id: 1])
        then:
            out instanceof Map
            'id' in out
            out['id'] == 1
            'status' in out
    }

    def "paged query returns data"() {
        when:
            def out = client.exec('buildRecords', 'getBuiltArtifacts', [id: 7113])
        then:
            out instanceof List
    }

    def "non-authed write returns Auth error"() {
        when:
            def out = client.exec(
                'buildTasks', 'cancelBbuild',
                [buildExecutionConfigurationId: 1]
            )
        then:
            thrown(AuthException)
    }
}
