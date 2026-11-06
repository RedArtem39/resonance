package io.github.redartem39.resonance.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class Release(val version: String, val name: String, val date: String, val prerelease: Boolean, val notes: String, val assetName: String, val url: String, val size: Long, val digest: String?)
class UpdateInfo(val ok: Boolean, val current: String, val error: String? = null, val releases: List<Release> = emptyList(), val latest: String? = null, val hasUpdate: Boolean = false)

/**
 * Update check and rollback via GitHub Releases (the Android build looks for Resonance-*.apk assets).
 * Nothing is installed silently: the APK is downloaded, its SHA-256 is checked against the digest GitHub reports,
 * and then Android's own installer asks for confirmation. Rolling back is the same with an older release.
 */
object Updater {
    private const val REPO = "RedArtem39/resonance"
    private const val UA = "Resonance-updater"
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(true).build()

    private fun norm(v: String): List<Int> = v.removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    fun cmp(a: String, b: String): Int {
        val x = norm(a); val y = norm(b)
        for (i in 0 until 3) { val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 }); if (d != 0) return d }
        return 0
    }

    private fun list(): List<Release> {
        val r = http.newCall(Request.Builder().url("https://api.github.com/repos/$REPO/releases?per_page=20")
            .header("User-Agent", UA).header("Accept", "application/vnd.github+json").build()).execute()
        r.use {
            if (it.code == 404) return emptyList()
            if (!it.isSuccessful) error("GitHub answered ${it.code}")
            val a = JSONArray(it.body!!.string())
            val out = ArrayList<Release>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.optBoolean("draft")) continue
                val assets = o.optJSONArray("assets") ?: continue
                var asset: org.json.JSONObject? = null
                for (j in 0 until assets.length()) {
                    val x = assets.getJSONObject(j)
                    if (Regex("^Resonance-.*\\.apk$", RegexOption.IGNORE_CASE).matches(x.optString("name"))) { asset = x; break }
                }
                if (asset == null) continue
                out += Release(
                    o.optString("tag_name").removePrefix("v"), o.optString("name").ifEmpty { o.optString("tag_name") }, o.optString("published_at"),
                    o.optBoolean("prerelease"), o.optString("body").trim().take(600),
                    asset.optString("name"), asset.optString("browser_download_url"), asset.optLong("size"), asset.optString("digest").ifEmpty { null },
                )
            }
            return out.sortedWith { a, b -> cmp(b.version, a.version) }
        }
    }

    suspend fun check(current: String): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val rel = list()
            val latest = rel.firstOrNull { !it.prerelease }
            UpdateInfo(true, current, releases = rel, latest = latest?.version, hasUpdate = latest != null && cmp(latest.version, current) > 0)
        } catch (e: Exception) { UpdateInfo(false, current, error = e.message ?: e.toString()) }
    }

    /** Downloads and verifies the release, then hands it to the system installer. Returns an explanatory message. */
    suspend fun install(ctx: Context, version: String, onProgress: (Int) -> Unit): String = withContext(Dispatchers.IO) {
        val rel = list().firstOrNull { it.version == version } ?: error("Этот релиз больше недоступен")
        val u = Uri.parse(rel.url)
        if (u.scheme != "https" || u.host != "github.com") error("Неожиданный адрес загрузки")
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, rel.assetName.replace(Regex("[^\\w.\\-]"), "_"))
        val md = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(rel.url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) error("Не скачалось (${r.code})")
            val total = r.body!!.contentLength().takeIf { it > 0 } ?: rel.size
            var got = 0L; var last = 0L
            r.body!!.byteStream().use { inp ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        os.write(buf, 0, n); md.update(buf, 0, n); got += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - last > 150) { last = now; onProgress(minOf(99, (got * 100 / total).toInt())) }
                    }
                }
            }
        }
        val want = rel.digest?.takeIf { it.startsWith("sha256:") }?.substring(7)?.lowercase()
        if (want != null && md.digest().joinToString("") { "%02x".format(it) } != want) { out.delete(); error("Контрольная сумма не совпала — файл удалён") }

        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext "Разреши установку из этого приложения и нажми ещё раз — файл уже скачан."
        }
        val uri = FileProvider.getUriForFile(ctx, "io.github.redartem39.resonance.files", out)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        "Скачано и проверено. Android спросит подтверждение установки."
    }
}
