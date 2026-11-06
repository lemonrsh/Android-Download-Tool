package dev.fetchly

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Multi-connection (segmented) downloader.
 *
 * 1. Probe the URL with "Range: bytes=0-0" to learn size + whether ranges work.
 * 2. Split into N segments, each fetched on its own connection and written at its own
 *    offset in a pre-allocated .part file.
 * 3. Segment positions are persisted, so pause / app-kill / retry all resume where they left off.
 * 4. On completion the file is copied into Downloads/Fetchly through MediaStore.
 */
object DownloadManager {
    private lateinit var app: Context
    private lateinit var store: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    @Volatile private var lastSave = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        store = File(app.filesDir, "downloads.json")
        runCatching {
            val arr = JSONArray(store.readText())
            _items.value = List(arr.length()) { Item.fromJson(arr.getJSONObject(it)) }.map {
                // The process died mid-download: show it as paused so the user can resume.
                if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED) it.copy(status = Status.PAUSED, speed = 0) else it
            }
        }
    }

    fun activeCount() = _items.value.count { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }

    fun ensureService() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, FetchlyService::class.java)) }
    }

    fun maybeStopService() {
        if (activeCount() == 0) app.stopService(Intent(app, FetchlyService::class.java))
    }

    // ---------------------------------------------------------------- public API

    /** Returns the download id, or null if [url] is not a valid http(s) URL. */
    fun add(
        url: String,
        fileName: String? = null,
        referer: String? = null,
        cookie: String? = null,
        userAgent: String? = null,
    ): String? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        val existing = _items.value.firstOrNull {
            it.url == u.toString() && it.status != Status.COMPLETED && it.status != Status.FAILED
        }
        if (existing != null) return existing.id
        val id = UUID.randomUUID().toString()
        val item = Item(
            id = id, url = u.toString(), fileName = sanitize(fileName ?: guessName(u)),
            referer = referer, cookie = cookie, userAgent = userAgent,
        )
        _items.update { listOf(item) + it }
        save(true)
        start(id)
        return id
    }

    fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        ensureService()
        jobs[id] = scope.launch {
            try {
                run(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutate(id) { it.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName, speed = 0) }
            } finally {
                save(true)
            }
        }
    }

    fun pause(id: String) {
        jobs[id]?.cancel()
        mutate(id) { if (it.status == Status.DOWNLOADING || it.status == Status.QUEUED) it.copy(status = Status.PAUSED, speed = 0) else it }
    }

    fun pauseAll() = _items.value.forEach { pause(it.id) }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        partFile(id).delete()
        _items.update { l -> l.filterNot { it.id == id } }
        save(true)
    }

    // ---------------------------------------------------------------- engine

    private suspend fun run(id: String) = coroutineScope {
        var item = get(id) ?: return@coroutineScope
        mutate(id) { it.copy(status = Status.DOWNLOADING, error = null) }
        if (item.segments.isEmpty()) item = probe(item)

        val part = partFile(id)
        val n = item.segments.size
        val starts = LongArray(n) { item.segments[it].start }
        val ends = LongArray(n) { item.segments[it].end }
        val pos = AtomicLongArray(LongArray(n) { item.segments[it].pos })

        // Can't resume from the middle without Range support, or if the partial file vanished.
        if (!item.rangeOk || !part.exists()) for (i in 0 until n) pos.set(i, starts[i])
        RandomAccessFile(part, "rw").use { if (item.total > 0 && it.length() != item.total) it.setLength(item.total) }

        fun downloadedNow(): Long = (0 until n).sumOf { i -> pos.get(i) - starts[i] }
        fun snapshot(status: Status? = null, speed: Long = 0) {
            val segs = List(n) { Segment(starts[it], ends[it], pos.get(it)) }
            val done = downloadedNow()
            mutate(id) { it.copy(segments = segs, downloaded = done, speed = speed, status = status ?: it.status) }
        }

        val ticker = launch {
            var last = downloadedNow()
            var lastT = System.nanoTime()
            while (isActive) {
                delay(500)
                val now = downloadedNow()
                val t = System.nanoTime()
                val speed = ((now - last) * 1_000_000_000L / (t - lastT).coerceAtLeast(1))
                last = now; lastT = t
                snapshot(speed = speed)
            }
        }

        try {
            coroutineScope {
                (0 until n).map { i -> async { fetchSegment(item, i, part, pos, ends) } }.awaitAll()
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { snapshot(if (e is CancellationException) Status.PAUSED else null) }
            throw e
        } finally {
            ticker.cancel()
        }
        snapshot()
        withContext(NonCancellable) { export(id, part) }
    }

    private suspend fun fetchSegment(item: Item, i: Int, part: File, pos: AtomicLongArray, ends: LongArray) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val start = pos.get(i)
            if (start > ends[i]) return
            try {
                val call = client.newCall(request(item).also {
                    if (item.rangeOk) {
                        val end = if (ends[i] == Long.MAX_VALUE) "" else ends[i].toString()
                        it.header("Range", "bytes=$start-$end")
                    }
                }.build())
                val reg = currentCoroutineContext()[Job]!!.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { resp ->
                        if (item.rangeOk && resp.code != 206) throw IOException("Server ignored range request (HTTP ${resp.code})")
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("Empty response")
                        RandomAccessFile(part, "rw").use { raf ->
                            raf.seek(start)
                            val buf = ByteArray(64 * 1024)
                            body.byteStream().use { ins ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = ins.read(buf)
                                    if (read < 0) break
                                    val take = if (ends[i] == Long.MAX_VALUE) read
                                    else minOf(read.toLong(), ends[i] - pos.get(i) + 1).toInt()
                                    raf.write(buf, 0, take)
                                    pos.addAndGet(i, take.toLong())
                                    if (take < read) break
                                }
                            }
                        }
                    }
                } finally {
                    reg.dispose()
                }
                if (ends[i] == Long.MAX_VALUE) return   // unknown length: EOF means done
                if (pos.get(i) <= ends[i]) throw IOException("Connection closed early")
                return
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()          // cancellation surfaces as IOException from OkHttp
                if (++attempt > 5) throw e
                delay(1000L * attempt)
            }
        }
    }

    private fun probe(item: Item): Item {
        client.newCall(request(item).header("Range", "bytes=0-0").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val total: Long
            val ranges: Boolean
            if (resp.code == 206) {
                total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                ranges = total > 0
            } else {
                total = resp.header("Content-Length")?.toLongOrNull() ?: -1
                ranges = false
            }
            val name = nameFromDisposition(resp.header("Content-Disposition"))?.let(::sanitize) ?: item.fileName
            val want = Prefs.segments(app).toLong()
            val n = if (ranges && total > 1_048_576) minOf(want, total / 524_288).toInt().coerceAtLeast(1) else 1
            val segs = if (n == 1) {
                listOf(Segment(0, if (total > 0) total - 1 else Long.MAX_VALUE, 0))
            } else {
                val size = total / n
                List(n) { i -> Segment(i * size, if (i == n - 1) total - 1 else (i + 1) * size - 1, i * size) }
            }
            mutate(item.id) { it.copy(total = total, rangeOk = ranges, fileName = name, segments = segs) }
        }
        return get(item.id)!!
    }

    private fun export(id: String, part: File) {
        val item = get(id) ?: return
        val ext = item.fileName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fetchly")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Downloads")
        resolver.openOutputStream(uri)!!.use { out -> part.inputStream().use { it.copyTo(out, 256 * 1024) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        part.delete()
        mutate(id) {
            it.copy(status = Status.COMPLETED, uri = uri.toString(), speed = 0,
                total = if (it.total > 0) it.total else it.downloaded, downloaded = if (it.total > 0) it.total else it.downloaded)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun request(item: Item): Request.Builder = Request.Builder().url(item.url).apply {
        header("User-Agent", item.userAgent ?: "Mozilla/5.0 (Android) Fetchly/0.1")
        item.referer?.let { header("Referer", it) }
        item.cookie?.let { header("Cookie", it) }
    }

    private fun partFile(id: String) = File(app.getExternalFilesDir(null) ?: app.filesDir, "$id.part")

    private fun get(id: String) = _items.value.firstOrNull { it.id == id }

    private fun mutate(id: String, f: (Item) -> Item) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it } }
        save()
    }

    private fun save(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 1000) return
        lastSave = now
        val arr = JSONArray()
        _items.value.forEach { arr.put(it.toJson()) }
        runCatching { store.writeText(arr.toString()) }
    }

    private fun guessName(u: HttpUrl): String {
        val seg = u.pathSegments.lastOrNull().orEmpty()
        val decoded = runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
        return decoded.ifBlank { u.host }
    }

    /** Strips path separators / control chars so a hostile server can't escape the folder. */
    private fun sanitize(name: String): String {
        val s = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.').take(200)
        return s.ifBlank { "download" }
    }

    private fun nameFromDisposition(h: String?): String? {
        if (h == null) return null
        Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(h)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
        }
        return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.trim()
    }
}
