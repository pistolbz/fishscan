package com.scanpdf.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface InstallEvent {
    data object WaitingForUser : InstallEvent
    data object Succeeded : InstallEvent
    data class Failed(val message: String) : InstallEvent
}

/** Process-wide bridge from [InstallResultReceiver] to the UI. */
object InstallEvents {
    private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<InstallEvent> = _events.asSharedFlow()
    fun emit(event: InstallEvent) {
        _events.tryEmit(event)
    }
}

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android asks the user to confirm; our app is in the foreground, so we may start it.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        InstallEvents.emit(InstallEvent.WaitingForUser)
                    } catch (e: Exception) {
                        Log.e("ScanPDF", "cannot open install confirmation", e)
                        InstallEvents.emit(InstallEvent.Failed("Không mở được màn hình cài đặt"))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvents.emit(InstallEvent.Succeeded)
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("ScanPDF", "install failed: status=$status $detail")
                InstallEvents.emit(InstallEvent.Failed(describe(status)))
            }
        }
    }

    private fun describe(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Đã hủy cài bản cập nhật"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Máy đang chặn cài đặt (chính sách quản lý thiết bị)"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Không cài đè được: bản cập nhật xung đột với bản đang cài"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Bản cập nhật không tương thích với máy này"
        PackageInstaller.STATUS_FAILURE_INVALID -> "File cập nhật không hợp lệ"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Không đủ bộ nhớ để cài bản cập nhật"
        else -> "Cài bản cập nhật thất bại"
    }
}
