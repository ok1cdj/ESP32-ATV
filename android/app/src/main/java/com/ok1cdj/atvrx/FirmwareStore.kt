package com.ok1cdj.atvrx

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Firmware for the boards, always the current one from the web (GitHub Pages, the same ESP Web Tools manifests as the
 * web flasher: builds[chipFamily].parts[path, offset], plus our sha256 and version), cached in the app's files for use
 * without a network. So a new firmware needs no new app.
 */
class FirmwareStore(private val ctx: Context, private val base: String = DEFAULT_BASE) {
    class Part(val offset: Int, val data: ByteArray)
    class Firmware(val chip: String, val name: String, val version: String, val parts: List<Part>, val fromCache: Boolean)

    /** The manifest of the board's firmware: ESP32-S3 the receiver, ESP32-C3 the transmitter. */
    private fun manifestUrl(chip: String) = if (chip == "ESP32-C3") "$base/c3/manifest.json" else "$base/manifest.json"

    /** Downloads (and caches) the firmware for [chip]; without a network the cached one, if any. */
    fun get(chip: String, log: (String) -> Unit): Firmware {
        val dir = File(ctx.filesDir, "firmware/$chip").apply { mkdirs() }
        return try {
            val url = manifestUrl(chip)
            val text = fetch(url).toString(Charsets.UTF_8)
            val (name, version, parts) = parse(text, chip)
            val data = parts.map { (path, offset, sha) ->
                val bytes = fetch(URL(URL(url), path).toString())
                if (sha != null && sha256(bytes) != sha) throw IOException("$path: sha256 differs from the manifest")
                File(dir, "%08x.bin".format(offset)).writeBytes(bytes)
                Part(offset, bytes)
            }
            File(dir, "manifest.json").writeText(text)
            log("downloaded $name $version")
            Firmware(chip, name, version, data, false)
        } catch (e: IOException) {
            val m = File(dir, "manifest.json")
            if (!m.exists()) throw IOException("no network and no saved firmware for $chip (${e.message})")
            val (name, version, parts) = parse(m.readText(), chip)
            log("offline: the saved $name $version (${e.message})")
            Firmware(chip, name, version, parts.map { (_, offset, _) -> Part(offset, File(dir, "%08x.bin".format(offset)).readBytes()) }, true)
        }
    }

    /** The version on the web without downloading the images (null without a network). */
    fun onlineVersion(chip: String): String? = try {
        parse(fetch(manifestUrl(chip)).toString(Charsets.UTF_8), chip).second
    } catch (_: IOException) { null }

    private fun parse(text: String, chip: String): Triple<String, String, List<Triple<String, Int, String?>>> {
        val j = JSONObject(text)
        val builds = j.getJSONArray("builds")
        for (i in 0 until builds.length()) {
            val b = builds.getJSONObject(i)
            if (b.getString("chipFamily") != chip) continue
            val ps = b.getJSONArray("parts")
            val parts = (0 until ps.length()).map { k ->
                val p = ps.getJSONObject(k)
                Triple(p.getString("path"), p.getInt("offset"), p.optString("sha256").ifEmpty { null })
            }
            return Triple(j.optString("name", chip), j.optString("version", "?"), parts)
        }
        throw IOException("the manifest has no build for $chip")
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.useCaches = false
        try {
            if (c.responseCode != 200) throw IOException("$url: HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_BASE = "https://ok1cdj.github.io/ESP32-ATV/flasher"
    }
}
