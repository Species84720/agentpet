package online.thenightwatcher.agentpet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks the project's GitHub prereleases and hands downloaded APKs to Android's installer. */
object AndroidUpdater {
    private const val RELEASES_API = "https://api.github.com/repos/Species84720/agentpet/releases?per_page=100"
    private const val APK_ASSET = "agentpet-android-debug.apk"
    private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

    data class Update(val build: Int, val versionName: String, val downloadUrl: String)

    fun check(onComplete: (Update?, String?) -> Unit) {
        val request = Request.Builder().url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AgentPet-Android")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = onComplete(null, error.message ?: "Network error")
            override fun onResponse(call: Call, response: Response) = response.use {
                if (!it.isSuccessful) {
                    onComplete(null, "GitHub returned HTTP ${it.code}")
                    return
                }
                val releases = runCatching { org.json.JSONArray(it.body?.string().orEmpty()) }.getOrNull()
                if (releases == null) {
                    onComplete(null, "Could not read release information")
                    return
                }
                val current = BuildConfig.VERSION_CODE
                var newest: Update? = null
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    val tag = release.optString("tag_name")
                    val build = Regex("^android-(\\d+)$").matchEntire(tag)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                    if (build <= current || build <= (newest?.build ?: 0)) continue
                    val assets = release.optJSONArray("assets") ?: continue
                    val asset = (0 until assets.length()).asSequence()
                        .mapNotNull(assets::optJSONObject)
                        .firstOrNull { it.optString("name") == APK_ASSET }
                    val url = asset?.optString("browser_download_url")?.takeIf { it.startsWith("https://") } ?: continue
                    newest = Update(build, "0.1.0-build$build", url)
                }
                onComplete(newest, null)
            }
        })
    }

    fun downloadAndInstall(context: Context, update: Update, onComplete: (String?) -> Unit) {
        val request = Request.Builder().url(update.downloadUrl).header("User-Agent", "AgentPet-Android").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) = onComplete(error.message ?: "Download failed")
            override fun onResponse(call: Call, response: Response) = response.use {
                if (!it.isSuccessful) {
                    onComplete("Download failed (HTTP ${it.code})")
                    return
                }
                val body = it.body ?: run { onComplete("GitHub returned an empty APK"); return }
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val apk = File(directory, "agentpet-${update.build}.apk")
                try {
                    body.byteStream().use { input -> apk.outputStream().use { output -> input.copyTo(output) } }
                    if (apk.length() < 1_000_000L) throw IOException("Downloaded APK is unexpectedly small")
                    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
                    val install = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(install)
                    onComplete(null)
                } catch (error: Exception) {
                    apk.delete()
                    onComplete(error.message ?: "Could not open Android's installer")
                }
            }
        })
    }

    fun canInstallPackages(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
}
