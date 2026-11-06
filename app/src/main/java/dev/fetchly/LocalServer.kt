package dev.fetchly

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Tiny HTTP server on 127.0.0.1 that the browser extension talks to.
 *
 * Protections against web pages / other apps abusing it:
 *  - loopback only
 *  - requests carrying an http(s) Origin header (i.e. from a web page) are rejected
 *  - Host header must be 127.0.0.1 / localhost (blocks DNS rebinding)
 *  - every request needs the secret pairing token in X-Fetchly-Token
 */
class LocalServer(private val ctx: Context) {
    companion object { const val PORT = 6877 }

    @Volatile private var server: ServerSocket? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val s = try {
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: IOException) { return }
        server = s
        thread(isDaemon = true, name = "fetchly-http") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { runCatching { handle(c) } }
            }
        }
    }

    @Synchronized
    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(sock: Socket) = sock.use { s ->
        s.soTimeout = 5000
        val inp = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()

        val parts = (readLine(inp) ?: return@use).split(" ")
        if (parts.size < 2) return@use
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > 64 * 1024) return@use reply(out, 413, err("too large"))
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = inp.read(body, read, len - read)
            if (n < 0) break
            read += n
        }

        val origin = headers["origin"]
        if (origin != null && (origin.startsWith("http://") || origin.startsWith("https://")))
            return@use reply(out, 403, err("web origins are not allowed"))
        val host = headers["host"]?.substringBefore(':')
        if (host != "127.0.0.1" && host != "localhost") return@use reply(out, 403, err("bad host"))
        val tok = headers["x-fetchly-token"] ?: ""
        if (!MessageDigest.isEqual(tok.toByteArray(), Prefs.token(ctx).toByteArray()))
            return@use reply(out, 401, err("bad token"))

        when {
            parts[0] == "GET" && parts[1] == "/ping" -> reply(out, 200, """{"ok":true,"app":"Fetchly"}""")
            parts[0] == "POST" && parts[1] == "/add" -> {
                val j = JSONObject(String(body, Charsets.UTF_8))
                val id = DownloadManager.add(
                    url = j.getString("url"),
                    fileName = j.optString("filename").ifEmpty { null },
                    referer = j.optString("referer").ifEmpty { null },
                    cookie = j.optString("cookie").ifEmpty { null },
                    userAgent = j.optString("userAgent").ifEmpty { null },
                )
                if (id != null) reply(out, 200, """{"ok":true,"id":"$id"}""") else reply(out, 400, err("invalid url"))
            }
            else -> reply(out, 404, err("not found"))
        }
    }

    private fun err(m: String) = """{"ok":false,"error":"$m"}"""

    private fun reply(out: OutputStream, code: Int, json: String) {
        val b = json.toByteArray()
        val text = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write("HTTP/1.1 $code $text\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
        out.flush()
    }

    private fun readLine(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }
}
