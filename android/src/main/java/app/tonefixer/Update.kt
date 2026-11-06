package app.tonefixer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates (PLAN.md §4.7). New versions are published as `latest.json` + the APK on the
 * repo's `updates` branch; we download the APK, check its SHA-256 and hand it to Android's own
 * installer, which shows its "Update this app?" screen. Android itself refuses an APK that isn't
 * signed with the same key as the installed app.
 */
object Update {
    const val LATEST_URL = "https://raw.githubusercontent.com/rasoulnorouzi/fluent_assistant/updates/android/latest.json"
    const val ACTION_STATUS = "app.tonefixer.INSTALL_STATUS"

    class Info(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String, val notes: String)

    /** [base] = where latest.json came from; a relative `apk` is resolved against it. */
    fun parse(json: String, base: String): Info {
        val o = JSONObject(json)
        return Info(
            versionCode = o.getLong("versionCode"),
            versionName = o.getString("versionName"),
            apkUrl = URI(base).resolve(o.getString("apk")).toString(),
            sha256 = o.getString("sha256").lowercase(),
            notes = o.optString("notes"),
        )
    }

    fun installedCode(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Blocking; call off the main thread. Throws IOException when offline or the file is bad. */
    fun check(url: String = LATEST_URL): Info {
        // A query string sidesteps stale caches between us and GitHub (raw files are cached ~5 min).
        val c = open("$url?t=${System.currentTimeMillis() / 60_000}")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val body = c.inputStream.bufferedReader().readText()
            return runCatching { parse(body, url) }.getOrElse { throw IOException("Bad update file") }
        } finally { c.disconnect() }
    }

    /** Downloads into [dir]/update.apk and checks the SHA-256. [progress] gets 0..100. Blocking. */
    fun download(info: Info, dir: File, progress: (Int) -> Unit): File {
        val out = File(dir, "update.apk")
        if (out.exists() && sha256(out) == info.sha256) return out // already downloaded
        val c = open(info.apkUrl)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        } finally { c.disconnect() }
        if (sha256(out) != info.sha256) { out.delete(); throw IOException("Download damaged, try again") }
        return out
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(32 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Hands the APK to Android's installer. The result comes back to SettingsActivity.onNewIntent
     * (action [ACTION_STATUS]): first "needs user action" with the system's confirm screen to show.
     */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val status = Intent(ctx, SettingsActivity::class.java).setAction(ACTION_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = PendingIntent.getActivity(ctx, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pi.intentSender)
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000
        setRequestProperty("Cache-Control", "no-cache")
    }
}
