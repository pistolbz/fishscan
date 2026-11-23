package com.scanpdf.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/** Verifies a downloaded APK and hands it to Android's PackageInstaller. */
object ApkInstaller {

    /** Whether the user allowed this app to install other apps ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Checks the APK is this app, newer than what is installed and signed with the same key.
     * Android would refuse such an APK anyway, but only with a vague "App not installed".
     * @return a user-facing problem description, or null when the APK is fine
     */
    fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = signatureFlags()
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return "File tải về không phải APK hợp lệ"
        if (archive.packageName != context.packageName) return "File tải về không phải bản cài của ScanPDF"

        val installed = pm.getPackageInfo(context.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            return "Bản tải về không mới hơn bản đang cài"
        }

        val newSigners = signerDigests(archive)
        val currentSigners = signerDigests(installed)
        // If the archive signers can't be read on this Android version, let the system enforce it.
        if (newSigners.isNotEmpty() && currentSigners.isNotEmpty() && newSigners.intersect(currentSigners).isEmpty()) {
            return "Bản cập nhật ký bằng khóa khác với bản đang cài (ví dụ đang dùng bản debug), " +
                "nên không thể cài đè. Hãy gỡ app rồi cài bản mới."
        }
        return null
    }

    /** Starts an install session; the result arrives in [InstallResultReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("ScanPDF.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            // Must be mutable: the system adds the status extras to this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(callback.intentSender)
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        return signatures.orEmpty().map {
            UpdateRepository.toHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }.toSet()
    }
}
