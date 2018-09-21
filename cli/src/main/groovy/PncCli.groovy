package ca.szc.groovy.pnc

import groovy.cli.picocli.CliBuilder
import groovy.cli.picocli.OptionAccessor
import groovy.transform.Memoized

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.OutputStreamAppender
import org.slf4j.LoggerFactory

class PncCli {

    private static setupLogging(
        OutputStream console,
        Integer verbosity=0,
        Integer httpDebug=0
    ) {
        LoggerContext context = LoggerFactory.getILoggerFactory()
        def loggers = context.loggerList
        context.reset();
        loggers.add(context.getLogger('ca.szc.groovy.pnc.PncClient'))
        loggers.add(context.getLogger('ca.szc.groovy.pnc.PncCli'))

        def logHttp = context.getLogger('groovyx.net.http.JavaHttpBuilder')
        def logHttpHeaders =  context.getLogger('groovy.net.http.JavaHttpBuilder.headers')
        def logHttpContent =  context.getLogger('groovy.net.http.JavaHttpBuilder.content')
        logHttp.level = Level.OFF
        logHttpHeaders.level = Level.OFF
        logHttpContent.level = Level.OFF
        switch (httpDebug) {
            case 3:
                logHttpContent.level = Level.DEBUG
            case 2:
                logHttpHeaders.level = Level.DEBUG
            case 1:
                logHttp.level = Level.DEBUG
                break
        }

        def pattern = '%-5level %logger{30} %msg%n'
        def level =  Level.OFF
        switch (verbosity) {
            case 1:
                level = Level.ERROR
                break
            case 2:
                level = Level.INFO
                break
            case { it >= 4 }:
                pattern = '%-5level %logger %msg%n%caller{3}%n'
            case 3:
                level = Level.DEBUG
                break
        }

        def encoder = new PatternLayoutEncoder()
        encoder.setContext(context)
        encoder.setPattern(pattern)
        encoder.start()

        def appender = new OutputStreamAppender()
        appender.setContext(context)
        appender.setName('console')
        appender.setEncoder(encoder)
        appender.setOutputStream(console)
        appender.start()

        loggers[0].addAppender(appender)
        loggers.each { log ->
            log.level = level
        }
    }

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
            usage:'pgc [-v] [-d] [-p PREFIX] {login,call,list}',
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
            v longOpt: 'verbose',
              'Make logging more verbose. Can be used multiple times.'
            d longOpt: 'http-debug',
              'Make HTTP logging more verbose. Can be used multiple times.'
            p longOpt: 'config-prefix',
              args: 1,
              argName: 'PREFIX',
              'Prepend this string to all configuration keys before reading their values. Can be used to store entries for multiple PNC instances (ex: prod, stage, devel) in one config file. If there is no key with a given prefix, the unprefixed key will be read instead. Example, with prefix "devel": devel.auth.realm || auth.realm'
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

    static Integer cli(
        args,
        OutputStream stderr=null,
        OutputStream stdout=null,
        Map<String,String> configOverride=null
    ) {
        stderr = stderr ?: System.err
        stdout = stdout ?: System.out
        def console = new PrintWriter(stderr, true)
        def dataOutput = new PrintWriter(stdout, true)

        def cli = commandRoot()
        def options = parse(cli, args, console, -1)
        if (!options) { return 1 }
        //console.println(">> ${options.arguments()}")

        setupLogging(stderr, (options.vs ?: []).size(), (options.ds ?: []).size())

        Map<String, String> config
        File cacheDir
        def getCfg = { key ->
            if (options.p) {
                return config["${options.p}.${key}"] ?: config[key]
            } else {
                return config[key]
            }
        }
        def readConfig = { ->
            if (configOverride) {
                config = configOverride
            } else {
                def configPath = homeFile(null, 'CONFIG', '.config/pgc.properties')
                def c = new Properties()
                if (configPath.exists()) {
                    c.load(configPath.newDataInputStream())
                }
                config = c
            }
            cacheDir = homeFile(getCfg('cache'), 'CACHE', ".cache/pgc")
        }
        def configMissing = { id ->
            if (!getCfg(id)) {
                console.println("error: Setting ${id} is missing from the config file")
                return true
            }
            return false
        }

        def pncClient
        def constructPncClient = { readOnly ->
            if (configMissing('pnc.url')) { return 4 }

            def auth
            if (readOnly) {
                auth = null
            } else {
                auth = Auth.retrieve(cacheDir, getCfg('auth.url'))
            }

            pncClient = new PncClient(
                getCfg('pnc.url'),
                cacheDir,
                auth,
            )
            return 0
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
                def code = constructPncClient(false)
                if (code > 0) { return code }

                try {
                    pncClient.execStream(
                        group,
                        operation,
                        dataOutput,
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
                def code = constructPncClient(true)
                if (code > 0) { return code }

                dataOutput.print(
                    pncClient.formatListing(
                        suboptions.e ?: /.*/,
                        consoleWidth(),
                        (suboptions.vs ?: []).size(),
                    )
                )
                // PrintWriter(s, true).print doesn't autoflush, since the
                // autoflush flag is for line-buffering, not zero buffering.
                dataOutput.flush()
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
                            getCfg('auth.url'),
                            getCfg('auth.realm'),
                            Auth.Grant.CLIENT,
                            prompt('Client ID', false),
                            prompt('Client Secret', true),
                            cacheDir,
                        )
                    } else {
                        Auth.store(
                            getCfg('auth.url'),
                            getCfg('auth.realm'),
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
        return 0
    }

    static void main(args) {
        System.exit(PncCli.cli(args))
    }
}
