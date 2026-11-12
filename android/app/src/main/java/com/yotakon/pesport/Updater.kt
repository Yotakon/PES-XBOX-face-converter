package com.yotakon.pesport

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualización desde la app: mira la release `ultima` de GitHub (la que publica GitHub Actions
 * en cada compilación de la rama app-android), descarga el APK y abre el instalador de Android.
 * Android solo instala el APK encima si está firmado con la misma clave que la app instalada.
 */
@Suppress("DEPRECATION")
object Updater {
    private const val RELEASE_URL = "https://api.github.com/repos/Yotakon/PES-XBOX-face-converter/releases/tags/ultima"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val DIR = "updates"

    /** Última versión publicada. */
    class Release(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val size: Long,
        val sha256: String?,
        val published: String,
    )

    class Installed(val versionCode: Long, val versionName: String)

    fun installed(ctx: Context): Installed {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return Installed(code, pi.versionName ?: "?")
    }

    /** El workflow escribe "versionCode=N" y "versionName=X" en las notas de la release. */
    internal fun parseNotes(body: String): Pair<Long, String>? {
        val code = Regex("versionCode=(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val name = Regex("versionName=([\\w.\\-]+)").find(body)?.groupValues?.get(1) ?: "?"
        return code to name
    }

    @Throws(IOException::class)
    fun fetchLatest(): Release {
        val c = open(RELEASE_URL)
        try {
            when (val code = c.responseCode) {
                200 -> {}
                404 -> throw IOException("Todavía no hay ninguna versión publicada.")
                403, 429 -> throw IOException("GitHub limita las consultas desde esta red. Prueba en unos minutos.")
                else -> throw IOException("GitHub respondió con el error $code.")
            }
            val json = try {
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: JSONException) {
                throw IOException("Respuesta de GitHub no válida.")
            }
            val (code, name) = parseNotes(json.optString("body"))
                ?: throw IOException("La versión publicada no indica su número. Se arreglará con la próxima compilación.")
            val assets = json.optJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apk = a
                    break
                }
            }
            if (apk == null) throw IOException("La versión publicada no tiene APK.")
            val digest = apk.optString("digest").removePrefix("sha256:").lowercase()
            return Release(
                code, name, apk.getString("browser_download_url"), apk.optLong("size", -1),
                digest.takeIf { it.length == 64 }, json.optString("published_at"),
            )
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "PESFaceConverter")
        return c
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, DIR)

    /** Borra APK descargados antes (no hacen falta una vez instalada la versión). */
    fun cleanup(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Descarga el APK a la caché de la app, comprueba su hash (si GitHub lo da) y que sea de esta
     * app con una versión mayor. [onProgress] recibe (bytes descargados, bytes totales o -1).
     */
    @Throws(IOException::class)
    fun download(ctx: Context, r: Release, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): File {
        cleanup(ctx)
        val d = dir(ctx).apply { mkdirs() }
        val part = File(d, "PESFaceConverter.apk.part")
        val apk = File(d, "PESFaceConverter.apk")
        val c = open(r.url)
        try {
            if (c.responseCode != 200) throw IOException("No se pudo descargar (error ${c.responseCode}).")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("Descarga cancelada.")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (r.size > 0 && done != r.size) throw IOException("La descarga quedó incompleta. Inténtalo otra vez.")
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256 != null && hex != r.sha256) throw IOException("El archivo descargado no coincide con el publicado.")
            if (!part.renameTo(apk)) throw IOException("No se pudo guardar el APK.")
            checkApk(ctx, apk)
            return apk
        } catch (e: IOException) {
            part.delete()
            apk.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun checkApk(ctx: Context, apk: File) {
        val pi = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("El archivo descargado no es un APK válido.")
        if (pi.packageName != ctx.packageName) throw IOException("El APK descargado es de otra aplicación.")
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        if (code <= installed(ctx).versionCode) throw IOException("El APK descargado no es más nuevo que el instalado.")
    }

    /** true si Android ya deja a esta app abrir el instalador (Android 8+ lo pide por app). */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun sizeText(b: Long): String = String.format(java.util.Locale.getDefault(), "%.1f MB", b / (1024.0 * 1024.0))

}
