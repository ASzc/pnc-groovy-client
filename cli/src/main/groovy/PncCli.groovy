package ca.szc.groovy.pnc

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor
import groovy.transform.Memoized

class PncCli {

    @Memoized
    private static Integer consoleWidth() {
        "tput cols".execute().text as Integer
    }

    private static OptionAccessor parse(cli, args, console, positionals=0) {
        cli.width = consoleWidth() - 4
        cli.writer = console
        cli.expandArgumentFiles = false
        if (positionals != -1) { cli.stopAtNonOption = false }
        def options = cli.parse(args)
        if (!options) { return null }
        if (options.h) {
            cli.usage()
            return null
        }
        if (positionals != -1 && options.arguments().size() != positionals) {
            cli.writer.println("error: Incorrect number of positional arguments")
            cli.usage()
            return null
        }
        return options
    }

    private static CliBuilder commandCall() {
        def cli = new CliBuilder(
            usage:'pgc call [-a ARGUMENT=VALUE] group operation',
            header:"""
                   Use an endpoint of the server's REST API. Run the list subcommand to see what endpoints are available.

                   positional arguments:
                    group      The group name of the endpoint
                    operation  The operation name of the endpoint

                   optional arguments:""".stripIndent(),
        )
        cli.with {
            h longOpt: 'help',
              'Show usage information'
            a longOpt: 'arg',
              type: Map,
              argName: 'ARGUMENT=VALUE',
              'Zero or more arguments for the endpoint Key=Value format. Value will be automatically coerced from a string to the appropriate type if needed by the endpoint.'
        }
        return cli
    }

    private static CliBuilder commandList() {
        def cli = new CliBuilder(
            usage:'pgc list [-e PATTERN]',
            header:"""
                   Print information on server API endpoints

                   positional arguments:

                   optional arguments:""".stripIndent(),
        )
        cli.with {
            h longOpt: 'help',
              'Show usage information'
            v longOpt: 'verbose',
              'Show more detailed information. Can be used multiple times.'
            e longOpt: 'regexp',
              args: 1,
              argName: 'PATTERN',
              'Show only endpoints with keys (group + operation) that contains this regex PATTERN'
        }
        return cli
    }

    private static CliBuilder commandLogin() {
        def cli = new CliBuilder(
            usage:'pgc login [-c]',
            header:"""
                   Interactively ask for the credentials, then store tokens for future use after authenticating with the server.

                   positional arguments:

                   optional arguments:""".stripIndent(),
        )
        cli.with {
            h longOpt: 'help',
              'Show usage information'
            c longOpt: 'client',
              'Authenticate for a client instead of a user'
        }
        return cli
    }

    private static CliBuilder commandRoot() {
        def cli = new CliBuilder(
            usage:'pgc [-d] {login,call,list}',
            header:"""
                   PNC Groovy Client, a CLI for the PNC REST API.

                   See the individual help messages of each subcommand for more information.

                   subcommand arguments:
                    {login,call,list}
                     login   Authenticate for those operations that need it
                     call    Use server API endpoint
                     list    Print information on server API endpoints

                   optional arguments:""".stripIndent(),
        )
        cli.with {
            h longOpt: 'help',
              'Show usage information'
            d longOpt: 'debug',
              'Enable debug logging'
        }
        return cli
    }

    private static File homeFile(String override, String envKey, String path) {
        def ret
        if (override != null) {
            ret = override
        } else {
            def env = System.getenv()["PGC_${envKey}"]
            if (env != null) {
                ret = env
            } else {
                ret = System.getProperty("user.home") + "/${path}".replace('/', File.separator)
            }
        }
        return new File(ret)
    }

    static Integer cli(args, PrintWriter console=null) {
        console = console ?: new PrintWriter(System.err)
        try {
            def cli = commandRoot()
            def options = parse(cli, args, console, -1)
            if (!options) { return 1 }
            //console.println(">> ${options.arguments()}")

            Properties config
            File cacheDir
            def readConfig = { ->
                def configPath = homeFile(null, 'CONFIG', '.config/pgc.properties')
                config = new Properties()
                if (configPath.exists()) {
                    config.load(configPath.newDataInputStream())
                }
                cacheDir = homeFile(config['cache'], 'CACHE', ".cache/pgc")
            }
            def configMissing = { id ->
                if (!config[id]) {
                    console.println("error: Setting ${id} is missing from the config file")
                    return true
                }
                return false
            }

            def pncClient
            def constructPncClient = { readOnly ->
                if (configMissing('pnc.url')) { return 4 }

                pncClient = new PncClient(
                    config['pnc.url'],
                    cacheDir,
                    readOnly ? null : Auth.retrieve(cacheDir),
                )
            }

            String subcommand = options.arguments()[0]
            def subargs = options.arguments().drop(1)
            switch (subcommand) {
                case 'call':
                    def suboptions = parse(commandCall(), subargs, console, 2)
                    if (!suboptions) { return 3 }

                    // Positional args -> method coordinates
                    def positionals = suboptions.arguments()
                    def group = positionals[0]
                    def operation = positionals[1]

                    // Optional args -> call keyword arguments
                    def callArgs = suboptions.as

                    readConfig()
                    constructPncClient(false)

                    try {
                        pncClient.execStream(
                            group,
                            operation,
                            new PrintWriter(System.out),
                            callArgs,
                        )
                    } catch (ModelCoerceException e) {
                        console.println(e.message)
                        return 5
                    } catch (ServerException e) {
                        console.println(e.message)
                        return 6
                    } catch (AuthException e) {
                        console.println(e.message)
                        return 7
                    }
                    break
                case 'list':
                    def suboptions = parse(commandList(), subargs, console)
                    if (!suboptions) { return 3 }

                    readConfig()
                    constructPncClient(true)

                    System.out.print(
                        pncClient.formatListing(
                            suboptions.e ?: /.*/,
                            consoleWidth(),
                            (suboptions.vs ?: []).size(),
                        )
                    )
                    break
                case 'login':
                    def suboptions = parse(commandLogin(), subargs, console)
                    if (!suboptions) { return 3 }

                    readConfig()

                    if (configMissing('auth.url')) { return 4 }
                    if (configMissing('auth.realm')) { return 4 }

                    def reader = null
                    def prompt = { name, disableEcho ->
                        def p = "${name}: "
                        def sysConsole = System.console()
                        String line
                        if (sysConsole == null) {
                            reader = reader ?: System.in.newReader()
                            line = reader.readLine()
                        } else {
                            if (disableEcho) {
                                line = sysConsole.readPassword(p)
                            } else {
                                line = sysConsole.readLine(p)
                            }
                        }
                        return line.trim()
                    }
                    if (reader) {
                        reader.close()
                    }

                    try {
                        if (suboptions.c) {
                            Auth.store(
                                config['auth.url'],
                                config['auth.realm'],
                                Auth.Grant.CLIENT,
                                prompt('Client ID', false),
                                prompt('Client Secret', true),
                                cacheDir,
                            )
                        } else {
                            Auth.store(
                                config['auth.url'],
                                config['auth.realm'],
                                Auth.Grant.USER,
                                prompt('Username', false),
                                prompt('Password', true),
                                cacheDir,
                            )
                        }
                    } catch (AuthException e) {
                        console.println("Unable to authenticate: ${e.message}")
                        return 7
                    }
                    break
                default:
                    console.println("error: Unknown subcommand ${subcommand}")
                    cli.usage()
                    return 2
            }
        } finally {
            console.flush()
        }
        return 0
    }

    static void main(args) {
        System.exit(PncCli.cli(args))
    }
}
