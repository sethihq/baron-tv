package com.barontv.remote.tv

import java.io.Closeable
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections

/**
 * A stand-in for the TV's remote server on a free local port: the JDK's own HTTP server
 * (com.sun.net.httpserver.HttpServer), answering "OK" to every request and noting each key code in the
 * order it arrives.
 *
 * Android's test compilers only see the Android API (android.jar), which has no com.sun.net.httpserver,
 * so the server is reached by reflection. The JVM that runs the tests has it. Methods are looked up on the
 * public com.sun.net.httpserver types: the classes behind them aren't open to reflection.
 */
internal class FakeTvServer : Closeable {
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
        val reply = "OK".toByteArray()
        exchangeType.getMethod("sendResponseHeaders", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(exchange, 200, reply.size.toLong())
        (exchangeType.getMethod("getResponseBody").invoke(exchange) as OutputStream).use { it.write(reply) }
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
