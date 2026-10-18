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

/** bytes != null: got the file. definitive: the server really said "no such file". */
private class Download(val bytes: ByteArray?, val definitive: Boolean)

/**
 * Finds real icons, banners and names for apps by package name, using public community
 * image libraries on GitHub, then keeps them on the headset so they only download once.
 * Every call blocks, so call it off the main thread.
 */
class ArtRepository(context: Context) {

    // New folder name on purpose: it drops the wrong "missing" notes saved by the old version.
    private val dir = File(context.cacheDir, "art2").apply { mkdirs() }
    private val mem = LruCache<String, ImageBitmap>(120)
    private val gate = Semaphore(4)

    private val missingForMs = 86_400_000L // remember a real "not found" for one day

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
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < missingForMs) return null

        var allDefinitive = true
