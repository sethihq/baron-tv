package com.barontv.home.bridge

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * One request to the bridge, from its request line: the method, the path and the query's parameters,
 * all URL-decoded (so `name=HDMI%201` reads "HDMI 1" and `order=a%2Cb` reads "a,b"). A path's trailing
 * slash is dropped, so `/input/` is `/input`; the first of a repeated parameter wins.
 */
class Request(val method: String, val path: String, val query: Map<String, String>) {

    operator fun get(name: String): String? = query[name]

    fun has(name: String): Boolean = name in query

    companion object {
        /** "GET /source?name=HDMI%201 HTTP/1.1" as a request, or null if the line isn't one. */
        fun parse(line: String): Request? {
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val method = parts[0].uppercase(Locale.ROOT)
            if (method.any { it !in 'A'..'Z' }) return null
            var target = parts[1].substringBefore('#')
            // A full URL (as a proxy would get it): only its path and query count.
            val scheme = target.indexOf("://")
            if (scheme > 0) {
                val rest = target.substring(scheme + 3)
                val slash = rest.indexOfAny(charArrayOf('/', '?'))
                target = if (slash < 0) "/" else rest.substring(slash)
            }
            if (!target.startsWith("/") && !target.startsWith("?")) return null
            val path = cleanPath(decode(target.substringBefore('?'), plusIsSpace = false))
            return Request(method, path, parseQuery(target.substringAfter('?', "")))
        }

        /** "a=1&b=x%20y&c" as {a=1, b=x y, c=}; '+' is a space here, as forms send it. */
        fun parseQuery(raw: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            if (raw.isEmpty()) return out
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val key = decode(pair.substringBefore('='), plusIsSpace = true)
                if (key.isEmpty() || key in out) continue
                out[key] = decode(pair.substringAfter('=', ""), plusIsSpace = true)
            }
            return out
        }

        /**
         * Percent-decodes [s]: each run of escapes is read as UTF-8 bytes ("%C3%A9" is "é"), everything else
         * is kept as it is, and a broken escape ("%zz", a lone "%") stays as written.
         */
        fun decode(s: String, plusIsSpace: Boolean): String {
            if (s.indexOf('%') < 0 && (!plusIsSpace || s.indexOf('+') < 0)) return s
            val out = StringBuilder(s.length)
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        bytes.write(hi * 16 + lo)
                        i += 3
                        continue
                    }
                }
                flush(bytes, out)
                out.append(if (c == '+' && plusIsSpace) ' ' else c)
                i++
            }
            flush(bytes, out)
            return out.toString()
        }

        private fun flush(bytes: ByteArrayOutputStream, out: StringBuilder) {
            if (bytes.size() == 0) return
            out.append(String(bytes.toByteArray(), Charsets.UTF_8))
            bytes.reset()
        }

        private fun cleanPath(path: String): String {
            val p = if (path.startsWith("/")) path else "/$path"
            return if (p.length > 1 && p.endsWith("/")) p.trimEnd('/').ifEmpty { "/" } else p
        }
    }
}
