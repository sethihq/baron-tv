package com.barontv.remote.tv

import java.io.Closeable
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections

/**
 * A stand-in for a server on the TV, on a free local port: the JDK's own HTTP server
 * (com.sun.net.httpserver.HttpServer). By default it answers "OK" to every request, as CloudWalker's
 * remote server does, and notes each key code in the order it arrives; [answer] makes it answer as
 * something else, such as the home's bridge.
 *
 * Android's test compilers only see the Android API (android.jar), which has no com.sun.net.httpserver,
 * so the server is reached by reflection. The JVM that runs the tests has it. Methods are looked up on the
 * public com.sun.net.httpserver types: the classes behind them aren't open to reflection.
 */
internal class FakeTvServer(
    /** The reply to a request for `path` (decoded) with `query` (as sent, or null). */
    private val answer: (path: String, query: String?) -> Answer = { _, _ -> Answer(200, "OK") },
) : Closeable {
    /** A reply: its status and its body. */
    class Answer(val status: Int, val body: String)

    /** Key codes received, oldest first. */
    val keycodes: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** Every request's path and query, as sent. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val port: Int
    val address: TvAddress get() = TvAddress("127.0.0.1", port)

    private val serverType = Class.forName("com.sun.net.httpserver.HttpServer")
    private val handlerType = Class.forName("com.sun.net.httpserver.HttpHandler")
    private val exchangeType = Class.forName("com.sun.net.httpserver.HttpExchange")
    private val server: Any

    init {
        val loopback = InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)
        server = serverType.getMethod("create", InetSocketAddress::class.java, Int::class.javaPrimitiveType)
            .invoke(null, loopback, 0)!!
        val handler = Proxy.newProxyInstance(handlerType.classLoader, arrayOf(handlerType)) { proxy, method, args ->
            when (method.name) {
                "handle" -> handle(args!![0])
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeTvServer handler"
                else -> null
            }
        }
        serverType.getMethod("createContext", String::class.java, handlerType).invoke(server, "/", handler)
        serverType.getMethod("start").invoke(server)
        port = (serverType.getMethod("getAddress").invoke(server) as InetSocketAddress).port
    }

    private fun handle(exchange: Any): Any? {
        val uri = exchangeType.getMethod("getRequestURI").invoke(exchange) as URI
        requests += uri.toString()
        Regex("""keycode=(\d+)""").find(uri.rawQuery ?: "")?.let { keycodes += it.groupValues[1].toInt() }
        val reply = answer(uri.path, uri.rawQuery)
        val body = reply.body.toByteArray()
        // For this server a length of 0 would mean "chunked"; -1 is how it says there's no body.
        exchangeType.getMethod("sendResponseHeaders", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(exchange, reply.status, if (body.isEmpty()) -1L else body.size.toLong())
        (exchangeType.getMethod("getResponseBody").invoke(exchange) as OutputStream).use { it.write(body) }
        exchangeType.getMethod("close").invoke(exchange)
        return null
    }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        serverType.getMethod("stop", Int::class.javaPrimitiveType).invoke(server, 0)
    }
}
