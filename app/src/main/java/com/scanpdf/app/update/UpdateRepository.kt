package com.scanpdf.app.update

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateException(message: String) : IOException(message)

/** Talks to GitHub: lists releases and downloads the APK. Blocking I/O, call from Dispatchers.IO. */
class UpdateRepository(private val context: Context, private val repo: String) {

    fun fetchReleases(): List<ReleaseInfo> {
        val conn = open("https://api.github.com/repos/$repo/releases?per_page=20")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                403, 429 -> throw UpdateException("GitHub tạm giới hạn số lần kiểm tra, hãy thử lại sau")
                404 -> throw UpdateException("Không tìm thấy repo cập nhật $repo")
                else -> throw UpdateException("GitHub trả lỗi $code")
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            return GitHubReleases.parse(json)
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads into the app cache, verifying SHA-256 when GitHub provided one. */
    suspend fun download(asset: ApkAsset, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val conn = open(asset.downloadUrl) // GitHub redirects to its CDN; same-protocol redirects are followed.
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateException("Không tải được bản cập nhật (lỗi ${conn.responseCode})")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            val actual = toHex(digest.digest())
            if (asset.sha256 != null && actual != asset.sha256) {
                file.delete()
                throw UpdateException("File tải về bị lỗi (sai mã SHA-256), hãy thử lại")
            }
            return file
        } catch (e: Throwable) {
            if (e !is UpdateException) file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ScanPDF-Updater")
        }

    companion object {
        private const val HEX = "0123456789abcdef"

        /** Locale-independent lower-case hex. */
        fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (b in bytes) {
                append(HEX[(b.toInt() shr 4) and 0xF])
                append(HEX[b.toInt() and 0xF])
            }
        }
    }
}
