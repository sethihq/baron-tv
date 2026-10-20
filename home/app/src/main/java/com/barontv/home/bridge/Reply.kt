package com.barontv.home.bridge

import org.json.JSONArray
import org.json.JSONObject

/** One answer from the bridge: a status, a type and a body, always UTF-8, and the connection closes after it. */
class Reply(val status: Int, val type: String, val body: String) {

    /** The whole HTTP response; Content-Length counts the body's bytes, not its characters. */
    fun bytes(): ByteArray {
        val content = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${content.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        return head.toByteArray(Charsets.US_ASCII) + content
    }

    companion object {
        const val TEXT = "text/plain; charset=utf-8"
        const val JSON = "application/json; charset=utf-8"

        fun text(text: String, status: Int = 200) = Reply(status, TEXT, text)

        fun json(value: JSONObject) = Reply(200, JSON, value.toString())

        fun json(value: JSONArray) = Reply(200, JSON, value.toString())

        val NOT_FOUND = text("Not found", 404)

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}
