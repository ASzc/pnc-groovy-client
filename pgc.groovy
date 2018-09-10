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
            }
        }
    }

    return root
}

//
// List Command
//

String formatListing(String swaggerUrl) {
    root = swaggerData(swaggerUrl)

    def out = new StringBuilder()

    root['tags'].each { tagData ->
        String tag = tagData['name']
        root['pathMethodsByTag'][tag].each { md ->
            String dashedOpId = dashSeparated(md.operationId)
            out << "${tag} ${dashedOpId}"
            out << '\n'
        }
        out << '\n'
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
        usage:'pgc call [-a ARGUMENT=VALUE] endpoint',
        header:"""
               Use server API endpoint

               positional arguments:
                endpoint   REST API endpoint TODOTODO

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
          'Arguments for the API endpoint Key=Value format'
    }
    return cli
}

CliBuilder commandList() {
    def cli = new CliBuilder(
        usage:'pgc list',
        header:"""
               Print information on server API endpoints

               positional arguments:

               optional arguments:
               """.stripIndent(),
    )
    cli.with {
        h longOpt: 'help',
          'Show usage information'
    }
    return cli
}

CliBuilder commandRoot() {
    def cli = new CliBuilder(
        usage:'pgc [-d] {call,list}',
        header:"""
               PNC Groovy Client, a CLI for the PNC REST API.

               positional arguments:
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

        String subcommand = options.arguments()[0]
        def subargs = options.arguments().drop(1)
        switch (subcommand) {
            case 'call':
                suboptions = parse(commandCall(), subargs, 1)
                if (!suboptions) { return 3 }
                if (!config.getProperty('pnc.url')) {
                    console.println('error: Setting pnc.url is missing from the config file')
                    return 4
                }
                break
            case 'list':
                suboptions = parse(commandList(), subargs)
                if (!suboptions) { return 3 }
                if (!config.getProperty('pnc.url')) {
                    console.println('error: Setting pnc.url is missing from the config file')
                    return 4
                }
                console.print(formatListing(config.getProperty('pnc.url') + '/swagger.json'))
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
