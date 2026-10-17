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

data class StoreMeta(
    val name: String?,
    val landscape: String?,
    val square: String?,
    val icon: String?,
    val hero: String?,
)

/**
 * Finds real icons, banners and names for apps by package name, using public community
 * image libraries on GitHub, then keeps them on the headset so they only download once.
 * Every call blocks, so call it off the main thread.
 */
class ArtRepository(context: Context) {

    private val dir = File(context.cacheDir, "art").apply { mkdirs() }
    private val mem = LruCache<String, ImageBitmap>(120)
    private val gate = Semaphore(4)

    private val exts = listOf("png", "jpg", "webp")
    private val communityIcons = listOf(
        "https://raw.githubusercontent.com/threethan/QuestLauncherImages/main/icon/",
        "https://raw.githubusercontent.com/veticia/binaries/main/icons/",
    )
    private val communityBanners = listOf(
        "https://raw.githubusercontent.com/threethan/QuestLauncherImages/main/banner/",
        "https://raw.githubusercontent.com/veticia/binaries/main/banners/",
    )
    private val metaBase = "https://raw.githubusercontent.com/threethan/MetaMetadata/main/data/common/"

    fun banner(pkg: String): ImageBitmap? {
        val extra = meta(pkg).let { listOfNotNull(it?.landscape, it?.hero) }
        return image("banner", pkg, communityBanners.flatMap { b -> exts.map { "$b$pkg.$it" } } + extra)
    }

    fun icon(pkg: String): ImageBitmap? {
        val extra = meta(pkg).let { listOfNotNull(it?.square, it?.icon) }
        return image("icon", pkg, communityIcons.flatMap { b -> exts.map { "$b$pkg.$it" } } + extra)
    }

    fun name(pkg: String): String? = meta(pkg)?.name?.takeIf { it.isNotBlank() }

    // ---- internals ----

    private fun image(kind: String, pkg: String, urls: List<String>): ImageBitmap? {
        val key = "$kind-$pkg"
        mem.get(key)?.let { return it }

        val file = File(dir, "$key.img")
        val none = File(dir, "$key.none")
        if (file.exists()) decode(file.readBytes())?.let { mem.put(key, it); return it }
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 2 * 86_400_000L) return null

        for (url in urls) {
            val bytes = download(url) ?: continue
            val bmp = decode(bytes) ?: continue
            runCatching { file.writeBytes(bytes) }
            mem.put(key, bmp)
            return bmp
        }
        runCatching { none.writeText("x") }
        return null
    }

    private fun meta(pkg: String): StoreMeta? {
        val file = File(dir, "meta-$pkg.json")
        val none = File(dir, "meta-$pkg.none")
        val text = when {
            file.exists() -> file.readText()
            none.exists() && System.currentTimeMillis() - none.lastModified() < 2 * 86_400_000L -> return null
            else -> {
                val bytes = download("$metaBase$pkg.json")
                if (bytes == null) {
                    runCatching { none.writeText("x") }
                    return null
                }
                String(bytes).also { runCatching { file.writeText(it) } }
            }
        }
        return runCatching {
            val o = JSONObject(text)
            fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k).takeIf { it.startsWith("http") } else null
            StoreMeta(
                name = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
                landscape = s("landscape"),
                square = s("square"),
                icon = s("icon"),
                hero = s("hero"),
            )
        }.getOrNull()
    }

    private fun decode(bytes: ByteArray) =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

    private fun download(url: String): ByteArray? {
        gate.acquireUninterruptibly()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.instanceFollowRedirects = true
            return try {
                if (c.responseCode != 200) null
                else c.inputStream.use { it.readBytes() }.takeIf { it.size in 1..4_000_000 }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            return null
        } finally {
            gate.release()
        }
    }
}
