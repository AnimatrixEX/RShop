package com.rshop.scraper.drive.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * The one-shot web server Google's sign-in page redirects to (`http://127.0.0.1:<port>/?code=…`).
 * It only listens on the loopback address, answers a short page telling the user to go back to
 * the app, and accepts a single answer whose `state` matches. Blocking: run it off the main thread.
 */
class LoopbackReceiver : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    val redirectUri: String = "http://127.0.0.1:${server.localPort}"

    sealed interface Result {
        data class Code(val code: String) : Result
        data class Failure(val error: String) : Result
    }

    /** Waits up to [timeoutMs] for Google's answer; throws [IOException] when it never comes. */
    fun await(expectedState: String, timeoutMs: Long): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        server.soTimeout = POLL_MS
        while (System.currentTimeMillis() < deadline) {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            }
            socket.use {
                it.soTimeout = READ_TIMEOUT_MS
                val requestLine = try {
                    it.getInputStream().bufferedReader().readLine().orEmpty()
                } catch (e: IOException) {
                    ""
                }
                val query = parse(requestLine)
                val out = it.getOutputStream()
                if (query == null) {
                    respond(out, 404, "Not found")
                    return@use
                }
                val result = when {
                    query["state"] != expectedState -> Result.Failure("state_mismatch")
                    query["error"] != null -> Result.Failure(query.getValue("error"))
                    query["code"] != null -> Result.Code(query.getValue("code"))
                    else -> null
                }
                if (result == null) {
                    respond(out, 404, "Not found")
                    return@use
                }
                respond(out, 200, PAGE)
                return result
            }
        }
        throw IOException("No answer from Google before the timeout")
    }

    /** The query parameters of "GET /?a=b HTTP/1.1" when it carries a code or an error, else null. */
    private fun parse(requestLine: String): Map<String, String>? {
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        val url = runCatching { "http://127.0.0.1${parts[1]}".toHttpUrl() }.getOrNull() ?: return null
        if (url.queryParameter("code") == null && url.queryParameter("error") == null) return null
        return url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() }
    }

    private fun respond(out: java.io.OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Not Found"
        out.write(
            ("HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII),
        )
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        runCatching { server.close() }
    }

    private companion object {
        const val POLL_MS = 500
        const val READ_TIMEOUT_MS = 5_000
        const val PAGE = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>RShop</title></head><body style=\"font-family:sans-serif;text-align:center;margin-top:20vh\">" +
            "<h2>RShop</h2><p>Connexion terminée. Fermez cet onglet pour retourner dans RShop.</p>" +
            "<p>Done. Close this tab to go back to RShop.</p></body></html>"
    }
}
