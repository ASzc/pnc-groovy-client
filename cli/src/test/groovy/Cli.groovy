package ca.szc.groovy.pnc

import spock.lang.*

class CliSpec extends Specification {

    def cli(String... args) {
        def o = new StringWriter()
        def code = PncCli.cli(args, new PrintWriter(o))
        return [code, o.toString()]
    }

    def "root returns help"() {
        when:
            def (code, out) = cli()
        then:
            code == 2
            out.startsWith('error: Unknown subcommand null\nUsage: pgc [-d] {login,call,list}')
    }

    def "root help returns help"() {
        when:
            def (code, out) = cli('-h')
        then:
            code == 1
            out.startsWith('Usage: pgc [-d] {login,call,list}')
    }

    def "invalid subcommand returns help"() {
        when:
            def (code, out) = cli('asd', '-h')
        then:
            code == 2
            out.startsWith('error: Unknown subcommand asd\nUsage: pgc [-d] {login,call,list}')
    }

    def "login help returns help"() {
        when:
            def (code, out) = cli('login', '-h')
        then:
            code == 3
            out.startsWith('Usage: pgc login [-c]')
    }

    def "call help returns help"() {
        when:
            def (code, out) = cli('call', '-h')
        then:
            code == 3
            out.startsWith('Usage: pgc call [-a ARGUMENT=VALUE] group operation')
    }

    def "list help returns help"() {
        when:
            def (code, out) = cli('list', '-h')
        then:
            code == 3
            out.startsWith('Usage: pgc list [-e PATTERN]')
    }
}
