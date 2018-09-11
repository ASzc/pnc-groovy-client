#!/usr/bin/env groovy

package ca.szc.groovy.pgc

import groovy.json.JsonSlurper
import groovy.transform.Memoized

import java.security.MessageDigest

//
// Misc
//

String sha256(String s) {
    MessageDigest.getInstance("SHA-256").digest(s.bytes).encodeHex().toString()
}

String dashSeparated(String s) {
    s.replaceAll(/\B[A-Z]/) { '-' + it }.toLowerCase()
}

String wordWrap(text, length=80, start=0) {
    length = length - start

    def sb = new StringBuilder()
    def line = ''

    text.split(/\s/).each { word ->
        if (line.size() + word.size() > length) {
            sb.append(line.trim()).append('\n').append(' ' * start)
            line = ''
        }
        line += " $word"
    }
    sb.append(line.trim()).toString()
}

//
// Model
//

@Memoized
LinkedHashMap swaggerData(String swaggerUrl) {
    File cached = new File(System.getenv()['PGC_CACHE'] ?: (System.getProperty("user.home") + "/.cache/pgc-${sha256(swaggerUrl)}.json"))

    if (!cached.exists()) {
        cached.withOutputStream { output ->
            swaggerUrl.toURL().withInputStream { input ->
                output << input
            }
        }
    }

    LinkedHashMap root = new JsonSlurper().parse(cached)

    // Calculate some indexes
    def pathMethodsByTag = [:]
    root['pathMethodsByTag'] = pathMethodsByTag
    root['paths'].each { path, pathData ->
        pathData.each { method, methodData ->
            methodData['tags'].each { tag ->
                if (!pathMethodsByTag.containsKey(tag)) {
                    pathMethodsByTag[tag] = []
                }
                // Add in some ease-of-use data too
                pathMethodsByTag[tag].add(methodData)
                methodData['path'] = path
                methodData['method'] = method
                methodData['operationIdDashed'] = dashSeparated(methodData['operationId'])
            }
        }
    }

    return root
}

//
// List Command
//

String formatListing(String swaggerUrl, String includePattern, Integer maxLength=80) {
    root = swaggerData(swaggerUrl)

    def out = new StringBuilder()

    String tag
    String prevTag = null
    root['tags'].each { tagData ->
        tag = tagData['name']
        root['pathMethodsByTag'][tag].each { md ->
            String key = "${tag} ${md.operationIdDashed}"
            if (key =~ includePattern) {
                // Add separator between different groups
                if (prevTag != null && tag != prevTag) {
                    out << '===\n\n'
                }
                prevTag = tag

                out << key
                md.parameters.each { paramData ->
                    if (paramData['required']) {
                        out << ' -a '
                        out << paramData['name']
                        out << '=V'
                    }
                }
                out << '\n'
                md.parameters.each { paramData ->
                    Integer start = out.length()
                    out << '  '
                    out << paramData['name']
                    out << ' ('
                    if (paramData['type']) {
                        // Normal parameter type
                        out << paramData['type']
                        out << ') '
                        out << wordWrap(
                            paramData['description'] ?: 'No description provided',
                            maxLength,
                            // Measure the length of the initial line info so we
                            // know where to wrap to.
                            out.length() - start,
                        )
                    } else if (paramData['schema']) {
                        // Schema parameter type
                        if (paramData['schema']['$ref']) {
                            String ref = paramData['schema']['$ref']
                            out << ref[ref.lastIndexOf('/') + 1..-1]
                        } else if (paramData['schema']['type']) {
                            out << paramData['schema']['type']
                            out << ' of '
                            String ref = paramData['schema']['items']['$ref']
                            out << ref[ref.lastIndexOf('/') + 1..-1]
                        } else {
                            throw new RuntimeException(
                                "Don't know how to handle type of parameter " +
                                "${paramData.name} of method ${key}"
                            )
                        }
                        out << ') JSON'
                    } else {
                        throw new RuntimeException(
                            "Don't know how to handle type of parameter " +
                            "${paramData.name} of method ${key}"
                        )
                    }
                    out << '\n'
                }
                // TODO return data
                out << '\n'
            }
        }
    }

    return out.toString()
}

//
// Call Command
//


//
// CLI
//

@Memoized
Integer consoleWidth() {
    "tput cols".execute().text as Integer
}

@Memoized
PrintWriter consoleWriter() {
    new PrintWriter(System.err)
}

OptionAccessor parse(cli, args, positionals=0) {
    cli.width = consoleWidth()
    cli.writer = consoleWriter()
    cli.expandArgumentFiles = false
    if (positionals != -1) { cli.stopAtNonOption = false }
    def options = cli.parse(args)
    if (!options) { return null }
    if (options.h || (positionals != -1 && options.arguments().size() != positionals)) {
        cli.usage()
        return null
    }
    return options
}

CliBuilder commandCall() {
    def cli = new CliBuilder(
        usage:'pgc call [-a ARGUMENT=VALUE] group operation',
        header:"""
               Use an endpoint of the server's REST API. Run the list subcommand to see what endpoints are available.

               positional arguments:
                group      The group name of the endpoint
                operation  The operation name of the endpoint

               optional arguments:
               """.stripIndent(),
    )
    cli.with {
        h longOpt: 'help',
          'Show usage information'
        a longOpt: 'arg',
          args: 2,
          valueSeparator: '=',
          argName: 'ARGUMENT=VALUE',
          'Zero or more arguments for the endpoint Key=Value format. Value will be automatically coerced from a string to the appropriate type if needed by the endpoint.'
    }
    return cli
}

CliBuilder commandList() {
    def cli = new CliBuilder(
        usage:'pgc list [-e PATTERN]',
        header:"""
               Print information on server API endpoints

               positional arguments:

               optional arguments:
               """.stripIndent(),
    )
    cli.with {
        h longOpt: 'help',
          'Show usage information'
        e longOpt: 'regexp',
          args: 1,
          argName: 'PATTERN',
          'Show only endpoints with keys (group + operation) that contains this regex PATTERN'
    }
    return cli
}

CliBuilder commandRoot() {
    def cli = new CliBuilder(
        usage:'pgc [-d] {call,list}',
        header:"""
               PNC Groovy Client, a CLI for the PNC REST API.

               See the individual help messages of each subcommand for more information.

               subcommand arguments:
                {call,list}
                 call    Use server API endpoint
                 list    Print information on server API endpoints

               optional arguments:
               """.stripIndent(),
    )
    cli.with {
        h longOpt: 'help',
          'Show usage information'
        d longOpt: 'debug',
          'Enable debug logging'
    }
    return cli
}

Properties readConfig(File config=null) {
    config = config ?: new File(System.getenv()['PGC_CONFIG'] ?: (System.getProperty("user.home") + '/.config/pgc.properties'))
    Properties props = new Properties()
    if (config.exists()) {
        props.load(config.newDataInputStream())
    }
    return props
}

Integer command(args) {
    PrintWriter console = consoleWriter()
    try {
        cli = commandRoot()
        options = parse(cli, args, -1)
        if (!options) { return 1 }
        //console.println(">> ${options.arguments()}")

        Properties config = readConfig()
        if (!config.getProperty('pnc.url')) {
            console.println('error: Setting pnc.url is missing from the config file')
            return 4
        }
        def pncUrl = config.getProperty('pnc.url')
        def swaggerUrl = pncUrl + '/swagger.json'

        String subcommand = options.arguments()[0]
        def subargs = options.arguments().drop(1)
        switch (subcommand) {
            case 'call':
                suboptions = parse(commandCall(), subargs, 1)
                if (!suboptions) { return 3 }
                break
            case 'list':
                suboptions = parse(commandList(), subargs)
                if (!suboptions) { return 3 }
                if (!config.getProperty('pnc.url')) {
                    console.println('error: Setting pnc.url is missing from the config file')
                    return 4
                }
                System.out.print(formatListing(
                    swaggerUrl,
                    suboptions.e ?: /.*/,
                    consoleWidth(),
                ))
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

System.exit(command(args))
