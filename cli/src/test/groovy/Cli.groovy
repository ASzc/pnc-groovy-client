package ca.szc.groovy.pnc

import spock.lang.*

class CliSpec extends Specification {

    def cli(String... args) {
        def o = new StringWriter()
        def code = PncCli.cli(['-h'], new PrintWriter(o))
        return [code, o.toString()]
    }

    def "root help returns help"() {
        when:
            def (code, out) = cli('-h')
        then:
            code == 1
            out.startsWith('Usage: pgc [-d] {login,call,list}')
    }
}
