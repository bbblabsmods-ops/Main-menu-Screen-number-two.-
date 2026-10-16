
package com.example.questlauncher

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode(val label: String) {
    NAME("Name (A–Z)"),
    RECENT("Recently updated"),
}

data class RefreshResult(val hz: Int, val applied: Boolean)

data class AdbUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Not connected",
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)
    private val adbController = AdbController(application)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _refreshResult = MutableStateFlow<RefreshResult?>(null)
    val refreshResult: StateFlow<RefreshResult?> = _refreshResult

    private val _adb = MutableStateFlow(AdbUiState())
    val adb: StateFlow<AdbUiState> = _adb

    fun refresh() {
        viewModelScope.launch {
            _apps.value = withContext(Dispatchers.IO) { repo.load() }
        }
    }

    fun launch(app: AppEntry) = repo.launch(app)

    fun openAppInfo(app: AppEntry) = repo.openAppInfo(app)

    fun moveTo(app: AppEntry, tab: LauncherTab) {
        repo.moveTo(app, tab)
        refresh()
    }

    /**
     * Uses the Wireless debugging connection when there is one. Without it we fall back to
     * trying directly, which Quest usually refuses, and the screen shows the command to copy.
     */
    fun setRefreshRate(hz: Int) {
        viewModelScope.launch {
            val supported = hz in listOf(60, 72, 90, 120)
            val ok = withContext(Dispatchers.IO) {
                if (adbController.isConnected()) {
                    adbController.shell("setprop debug.oculus.refreshRate $hz").isSuccess && supported
                } else {
                    repo.setRefreshRate(hz)
                }
            }
            _refreshResult.value = RefreshResult(hz, ok)
        }
    }

    fun dismissRefreshResult() {
        _refreshResult.value = null
    }

    fun openAndroidSettings() {
        if (!repo.openAndroidSettings()) toast("Couldn't open Android Settings")
    }

    fun openDeveloperSettings() {
        if (!repo.openDeveloperSettings()) toast("Couldn't open developer options")
    }

    fun openYouTubeVr() {
        if (!repo.openPackage("com.google.android.apps.youtube.vr.oculus")) {
            toast("YouTube VR isn't installed")
        }
    }

    // ---- Wireless debugging ----

    fun adbNote(message: String) {
        _adb.value = _adb.value.copy(message = message)
    }

    fun refreshAdbState() {
        viewModelScope.launch {
            val connected = withContext(Dispatchers.IO) { adbController.isConnected() }
            val old = _adb.value
            _adb.value = when {
                connected -> old.copy(connected = true, message = "Connected")
                old.connected -> old.copy(connected = false, message = "Connection lost. Tap Connect.")
                else -> old
            }
        }
    }

    /** Runs once when the launcher opens. Silent if it can't connect. */
    fun autoConnectQuietly() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                adbController.isConnected() || adbController.connect("127.0.0.1", null).isSuccess
            }
            if (ok) _adb.value = AdbUiState(connected = true, message = "Connected")
        }
    }

    fun adbPair(host: String, port: Int?, code: String) {
        if (host.isEmpty()) return adbNote("Type the IP address (127.0.0.1 works on this headset).")
        if (port == null) return adbNote("Type the pairing port (numbers only).")
        if (code.length < 4) return adbNote("Type the 6-digit pairing code.")
        viewModelScope.launch {
            _adb.value = _adb.value.copy(busy = true, message = "Pairing…")
            val result = withContext(Dispatchers.IO) { adbController.pair(host, port, code) }
            _adb.value = _adb.value.copy(
                busy = false,
                message = result.fold(
                    onSuccess = { "Paired. Now tap Connect." },
                    onFailure = { "Pairing failed: ${it.friendly()}" },
                ),
            )
        }
    }

    fun adbConnect(host: String, port: Int?) {
        if (host.isEmpty()) return adbNote("Type the IP address (127.0.0.1 works on this headset).")
        viewModelScope.launch {
            _adb.value = _adb.value.copy(busy = true, message = "Connecting…")
            val result = withContext(Dispatchers.IO) { adbController.connect(host, port) }
            _adb.value = result.fold(
                onSuccess = { AdbUiState(connected = true, busy = false, message = "Connected") },
                onFailure = {
                    AdbUiState(connected = false, busy = false, message = "Connect failed: ${it.friendly()}")
                },
            )
        }
    }

    fun adbDisconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { adbController.disconnect() }
            _adb.value = AdbUiState(connected = false, busy = false, message = "Disconnected")
        }
    }

    private fun Throwable.friendly(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun toast(message: String) {
        Toast.makeText(getApplication<Application>(), message, Toast.LENGTH_SHORT).show()
    }
}
