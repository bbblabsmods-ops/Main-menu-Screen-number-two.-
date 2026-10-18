package com.example.questlauncher

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore

class ArtDownload(val bytes: ByteArray?, val definitive: Boolean)

class ArtRepository(context: Context) {

    private val dir = File(context.cacheDir, "art2").apply { mkdirs() }
    private val mem = LruCache<String, ImageBitmap>(120)
    private val gate = Semaphore(4)
    private val oneDay = 86_400_000L
    private val exts = listOf("png", "jpg", "webp")
    private val raw = "https://raw.githubusercontent.com/"
    private val iconBases = listOf(raw + "threethan/QuestLauncherImages/main/icon/", raw + "veticia/binaries/main/icons/")
    private val bannerBases = listOf(raw + "threethan/QuestLauncherImages/main/banner/", raw + "veticia/binaries/main/banners/")
    private val metaBase = raw + "threethan/MetaMetadata/main/data/common/"

    fun banner(pkg: String): ImageBitmap? {
        val m = meta(pkg)
        val extra = listOfNotNull(m?.optString("landscape"), m?.optString("hero"))
        return image("banner", pkg, bannerBases, extra)
    }

    fun icon(pkg: String): ImageBitmap? {
        val m = meta(pkg)
        val extra = listOfNotNull(m?.optString("square"), m?.optString("icon"))
        return image("icon", pkg, iconBases, extra)
    }

    fun name(pkg: String): String? {
        val n = meta(pkg)?.optString("name")
        return if (n.isNullOrBlank()) null else n
    }

    private fun image(kind: String, pkg: String, bases: List<String>, extra: List<String>): ImageBitmap? {
        val key = "$kind-$pkg"
        mem.get(key)?.let { return it }
        val file = File(dir, "$key.img")
        val none = File(dir, "$key.none")
        if (file.exists()) {
            val d = decode(file.readBytes())
            if (d != null) {
                mem.put(key, d)
                return d
            }
        }
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < oneDay) return null

        val urls = bases.flatMap { b -> exts.map { e -> "$b$pkg.$e" } } + extra.filter { it.startsWith("http") }
        var allDefinitive = true
        for (url in urls) {
            val r = download(url)
            val data = r.bytes
            if (data == null) {
                if (!r.definitive) allDefinitive = false
                continue
            }
            val bmp = decode(data) ?: continue
            runCatching { file.writeBytes(data) }
            mem.put(key, bmp)
            return bmp
        }
        if (allDefinitive) runCatching { none.writeText("x") }
        return null
    }

    private fun meta(pkg: String): JSONObject? {
        val file = File(dir, "meta-$pkg.json")
        val none = File(dir, "meta-$pkg.none")
        var text: String? = if (file.exists()) file.readText() else null
        if (text == null) {
            if (none.exists() && System.currentTimeMillis() - none.lastModified() < oneDay) return null
            val r = download("$metaBase$pkg.json")
            val data = r.bytes
            if (data == null) {
                if (r.definitive) runCatching { none.writeText("x") }
                return null
            }
            text = String(data)
            runCatching { file.writeText(text) }
        }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

    private fun download(url: String): ArtDownload {
        gate.acquireUninterruptibly()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            try {
                val code = c.responseCode
                if (code == 200) {
                    val data = c.inputStream.use { it.readBytes() }
                    return ArtDownload(if (data.size in 1..4_000_000) data else null, true)
                }
                return ArtDownload(null, code in 400..499 && code != 429 && code != 408)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            return ArtDownload(null, false)
        } finally {
            gate.release()
        }
    }
}
