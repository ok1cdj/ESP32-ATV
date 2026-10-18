package com.ok1cdj.atvrx

import android.content.Context
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Is there something newer? At most once a day (switch off in the menu): the newest release of the app on GitHub
 * (pre-releases too, all ours are betas) and the firmware versions on the web (the manifests of the web flasher) against
 * the versions of the boards the app has seen – the C3 tells its own (INFO: "FW v… no-builtin-beacon"), the S3 does not
 * answer text, so for it the version the app last wrote into it. A board the app knows nothing about is not reported.
 */
object UpdateCheck {
    private const val PREFS = "updates"
    private const val RELEASES = "https://api.github.com/repos/ok1cdj/ESP32-ATV/releases?per_page=5"
    private const val DAY_MS = 24 * 3600 * 1000L

    class Result(val app: Release?, val boards: List<Board>) {
        val empty get() = app == null && boards.isEmpty()
    }
    class Release(val version: String, val url: String)
    /** a board whose firmware differs from the one on the web */
    class Board(val chip: String, val mac: String, val has: String, val web: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()

    /** The firmware a board has: after writing it (both chips) or from the C3's FW line. */
    fun rememberBoard(ctx: Context, mac: String?, chip: String, version: String) {
        if (mac == null || version.isBlank()) return
        prefs(ctx).edit().putString("board.$mac", "$chip|$version").apply()
    }

    /** The C3's "FW v0.2.0-sa-21-g3ad32fc no-builtin-beacon": only the web build is compared, not a developer's own build. */
    fun rememberC3(ctx: Context, mac: String?, fwLine: String?) {
        val p = fwLine?.trim()?.split(' ') ?: return
        if (p.size >= 3 && p[0] == "FW" && p[2] == "no-builtin-beacon") rememberBoard(ctx, mac, "ESP32-C3", p[1])
        else if (mac != null) prefs(ctx).edit().remove("board.$mac").apply()
    }

    /** The known boards whose firmware differs from [web] (chip → version on the web). */
    fun outdated(ctx: Context, web: Map<String, String>): List<Board> =
        prefs(ctx).all.mapNotNull { (k, v) ->
            if (!k.startsWith("board.") || v !is String) return@mapNotNull null
            val (chip, has) = v.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val w = web[chip] ?: return@mapNotNull null
            if (w != has) Board(chip, k.removePrefix("board."), has, w) else null
        }

    /** The last firmware versions seen on the web (for the TX screen, without a network). */
    fun webVersion(ctx: Context, chip: String): String? = prefs(ctx).getString("web.$chip", null)

    /**
     * Network, call off the UI thread. Null when it is not time yet (once a day), switched off or offline; otherwise
     * what is new, without the app version the user chose to skip.
     */
    fun run(ctx: Context, current: String, force: Boolean = false): Result? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && (!enabled(ctx) || now - p.getLong("last", 0) < DAY_MS)) return null
        val web = HashMap<String, String>()
        val store = FirmwareStore(ctx)
        for (chip in listOf("ESP32-S3", "ESP32-C3")) store.onlineVersion(chip)?.let { web[chip] = it }
        val app = try { newestRelease() } catch (_: Exception) { null }
        if (app == null && web.isEmpty()) return null                  // offline: try again next time
        val e = p.edit().putLong("last", now)
        web.forEach { (chip, v) -> e.putString("web.$chip", v) }
        e.apply()
        val newer = app?.takeIf { isNewer(it.version, current) && (force || it.version != p.getString("skip", null)) }
        return Result(newer, outdated(ctx, web))
    }

    fun skip(ctx: Context, version: String) = prefs(ctx).edit().putString("skip", version).apply()

    private fun newestRelease(): Release? {
        val c = URL(RELEASES).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val a = JSONArray(c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                return Release(r.getString("tag_name").removePrefix("v"), r.getString("html_url"))
            }
            return null
        } finally { c.disconnect() }
    }

    /**
     * "0.12-beta" newer than "0.11-beta"? The leading numbers decide; with the same numbers a release without a suffix
     * is newer than a beta. A CI build ("v0.11-beta-3-gabc1234-ci") counts as its tag; a local build ("…-dev") is
     * never told to update.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        if (current.endsWith("-dev")) return false
        val a = numbers(candidate) ?: return false
        val b = numbers(current.removePrefix("v")) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return '-' !in candidate && '-' in current.removePrefix("v")
    }

    private fun numbers(v: String): List<Int>? =
        Regex("""^(\d+(?:\.\d+)*)""").find(v)?.groupValues?.get(1)?.split('.')?.map { it.toInt() }
}
