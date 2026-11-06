package dev.fetchly

import org.json.JSONArray
import org.json.JSONObject

enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/** One byte range of a download. [pos] is the next byte to fetch; the segment is done when pos > end. */
data class Segment(val start: Long, val end: Long, val pos: Long)

data class Item(
    val id: String,
    val url: String,
    val fileName: String,
    val referer: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val total: Long = -1,
    val downloaded: Long = 0,
    val status: Status = Status.QUEUED,
    val rangeOk: Boolean = false,
    val segments: List<Segment> = emptyList(),
    val error: String? = null,
    val speed: Long = 0,
    val uri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val category: String get() = categoryOf(fileName)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("fileName", fileName)
        put("referer", referer); put("cookie", cookie); put("userAgent", userAgent)
        put("total", total); put("downloaded", downloaded)
        put("status", status.name); put("rangeOk", rangeOk)
        put("segments", JSONArray().also { arr ->
            segments.forEach { arr.put(JSONArray(listOf(it.start, it.end, it.pos))) }
        })
        put("error", error); put("uri", uri); put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Item {
            val segArr = o.optJSONArray("segments") ?: JSONArray()
            return Item(
                id = o.getString("id"),
                url = o.getString("url"),
                fileName = o.getString("fileName"),
                referer = o.optString("referer").ifEmpty { null },
                cookie = o.optString("cookie").ifEmpty { null },
                userAgent = o.optString("userAgent").ifEmpty { null },
                total = o.optLong("total", -1),
                downloaded = o.optLong("downloaded", 0),
                status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PAUSED),
                rangeOk = o.optBoolean("rangeOk", false),
                segments = List(segArr.length()) {
                    val s = segArr.getJSONArray(it)
                    Segment(s.getLong(0), s.getLong(1), s.getLong(2))
                },
                error = o.optString("error").ifEmpty { null },
                uri = o.optString("uri").ifEmpty { null },
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}

val CATEGORIES = listOf("All", "Video", "Audio", "Compressed", "Documents", "Programs", "Other")

fun categoryOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mkv", "webm", "avi", "mov", "flv", "m4v" -> "Video"
    "mp3", "m4a", "flac", "wav", "ogg", "aac", "opus" -> "Audio"
    "zip", "rar", "7z", "gz", "tgz", "tar", "xz", "bz2", "iso" -> "Compressed"
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub" -> "Documents"
    "apk", "exe", "msi", "dmg", "deb", "rpm", "appimage" -> "Programs"
    else -> "Other"
}

fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

fun fmtSpeed(b: Long) = fmtBytes(b) + "/s"
