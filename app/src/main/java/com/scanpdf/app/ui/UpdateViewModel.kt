package com.scanpdf.app.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanpdf.app.BuildConfig
import com.scanpdf.app.update.ApkInstaller
import com.scanpdf.app.update.GitHubReleases
import com.scanpdf.app.update.InstallEvent
import com.scanpdf.app.update.InstallEvents
import com.scanpdf.app.update.ReleaseInfo
import com.scanpdf.app.update.SemVer
import com.scanpdf.app.update.UpdateException
import com.scanpdf.app.update.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface UpdateUi {
    data object Hidden : UpdateUi
    data object Checking : UpdateUi
    data class Available(val release: ReleaseInfo) : UpdateUi
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateUi
    /** APK is ready but the user still has to allow "Install unknown apps" for ScanPDF. */
    data class NeedsPermission(val release: ReleaseInfo, val apk: File) : UpdateUi
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "ScanPDF"
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val PREF_LAST_CHECK = "last_check"
        /** Internal app: testers should also get releases marked "pre-release" on GitHub. */
        const val INCLUDE_PRERELEASES = true
    }

    private val repository = UpdateRepository(app, BuildConfig.UPDATE_REPO)
    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)

    val currentVersion: String = BuildConfig.VERSION_NAME

    private val _ui = MutableStateFlow<UpdateUi>(UpdateUi.Hidden)
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            InstallEvents.events.collect { event ->
                when (event) {
                    InstallEvent.WaitingForUser -> Unit
                    InstallEvent.Succeeded -> _message.value = "Đã cập nhật xong"
                    is InstallEvent.Failed -> _message.value = event.message
                }
            }
        }
        // Debug builds are signed with a different key and could never install a release over themselves.
        val due = System.currentTimeMillis() - prefs.getLong(PREF_LAST_CHECK, 0) > AUTO_CHECK_INTERVAL_MS
        if (!BuildConfig.DEBUG && due) check(silent = true)
    }

    /** @param silent automatic check: only speak up when an update exists */
    fun check(silent: Boolean = false) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            if (!silent) _ui.value = UpdateUi.Checking
            try {
                val releases = withContext(Dispatchers.IO) { repository.fetchReleases() }
                prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
                val current = SemVer.parse(currentVersion) ?: SemVer(0, 0, 0)
                val update = GitHubReleases.pickUpdate(releases, current, INCLUDE_PRERELEASES)
                when {
                    update != null -> _ui.value = UpdateUi.Available(update)
                    else -> {
                        _ui.value = UpdateUi.Hidden
                        if (!silent) _message.value = "Bạn đang dùng bản mới nhất ($currentVersion)"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                _ui.value = UpdateUi.Hidden
                if (!silent) _message.value = (e as? UpdateException)?.message ?: "Không kiểm tra được cập nhật, hãy kiểm tra kết nối mạng"
            }
        }
    }

    fun download(release: ReleaseInfo) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = UpdateUi.Downloading(release, 0f)
            try {
                val apk = withContext(Dispatchers.IO) {
                    repository.download(release.apk) { p -> _ui.value = UpdateUi.Downloading(release, p) }
                }
                val problem = withContext(Dispatchers.IO) { ApkInstaller.verify(getApplication(), apk) }
                if (problem != null) {
                    apk.delete()
                    _ui.value = UpdateUi.Hidden
                    _message.value = problem
                    return@launch
                }
                install(release, apk)
            } catch (e: CancellationException) {
                _ui.value = UpdateUi.Hidden
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                _ui.value = UpdateUi.Hidden
                _message.value = (e as? UpdateException)?.message ?: "Tải bản cập nhật thất bại, hãy thử lại"
            }
        }
    }

    /** Called when the app comes back to the foreground, e.g. from the "Install unknown apps" screen. */
    fun onResume() {
        val state = _ui.value
        if (state is UpdateUi.NeedsPermission && ApkInstaller.canInstall(getApplication())) {
            viewModelScope.launch { install(state.release, state.apk) }
        }
    }

    fun dismiss() {
        job?.cancel()
        _ui.value = UpdateUi.Hidden
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun install(release: ReleaseInfo, apk: File) {
        if (!ApkInstaller.canInstall(getApplication())) {
            _ui.value = UpdateUi.NeedsPermission(release, apk)
            return
        }
        _ui.value = UpdateUi.Hidden
        try {
            withContext(Dispatchers.IO) { ApkInstaller.install(getApplication(), apk) }
        } catch (e: Exception) {
            Log.e(TAG, "install session failed", e)
            _message.value = "Không bắt đầu cài đặt được: ${e.message}"
        }
    }
}
