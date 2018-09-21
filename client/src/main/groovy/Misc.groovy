package ca.szc.groovy.pnc

import groovy.transform.PackageScope

import java.security.MessageDigest

@PackageScope
class Misc {

    static String sha256(String s) {
        MessageDigest.getInstance("SHA-256").digest(s.bytes).encodeHex().toString()
    }

    static String dashSeparated(String s) {
        s.replaceAll(/\B[A-Z]/) { '-' + it }.toLowerCase()
    }

    static String camelCase(String s) {
        s.replaceAll(/-\w/){ it[1].toUpperCase() }
    }

    static String wordWrap(text, length=80, start=0) {
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
}
