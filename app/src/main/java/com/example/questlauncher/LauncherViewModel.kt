package com.example.questlauncher

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val autoConnect: Boolean = true,
)

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)
    private val adbController = AdbController(application)

    // Only one Wireless debugging job runs at a time.
    private val adbMutex = Mutex()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _refreshResult = MutableStateFlow<RefreshResult?>(null)
    val refreshResult: StateFlow<RefreshResult?> = _refreshResult

    private val _adb = MutableStateFlow(AdbUiState(autoConnect = adbController.autoConnectEnabled))
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
     * Uses the Wireless debugging connection (reconnecting first if needed). Without one we
     * fall back to trying directly, which Quest usually refuses, and the screen shows the
     * command to copy.
     */
    fun setRefreshRate(hz: Int) {
        viewModelScope.launch {
            val supported = hz in listOf(60, 72, 90, 120)
            adbMutex.withLock {
                val outcome = withContext(Dispatchers.IO) {
                    var connected = adbController.isConnected()
                    if (!connected && adbController.autoConnectEnabled) {
                        connected = adbController.autoConnect().isSuccess
                    }
                    val ok = if (connected) {
                        adbController.shell("setprop debug.oculus.refreshRate $hz").isSuccess && supported
                    } else {
                        repo.setRefreshRate(hz)
                    }
                    Pair(connected, ok)
                }
                _adb.value = if (outcome.first) {
                    _adb.value.copy(connected = true, message = "Connected")
                } else {
                    _adb.value.copy(connected = false)
                }
                _refreshResult.value = RefreshResult(hz, outcome.second)
            }
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

    fun setAutoConnect(on: Boolean) {
        adbController.autoConnectEnabled = on
        _adb.value = _adb.value.copy(autoConnect = on)
        if (on) autoConnectQuietly()
    }

    fun refreshAdbState() {
        if (_adb.value.busy) return
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

    /** Runs when the launcher opens. Quiet: it only updates the status line. */
    fun autoConnectQuietly() {
        if (!adbController.autoConnectEnabled) return
        val s = _adb.value
        if (s.connected || s.busy) return
        viewModelScope.launch {
            adbMutex.withLock {
                if (_adb.value.connected) return@withLock
                _adb.value = _adb.value.copy(busy = true, message = "Looking for Wireless debugging…")
                val result = withContext(Dispatchers.IO) { adbController.autoConnect() }
                _adb.value = result.fold(
                    onSuccess = { _adb.value.copy(connected = true, busy = false, message = "Connected") },
                    onFailure = {
                        _adb.value.copy(
                            connected = false,
                            busy = false,
                            message = "Not connected. Auto-connect: ${it.friendly()}",
                        )
                    },
                )
            }
        }
    }

    fun adbPair(host: String, port: Int?, code: String) {
        if (host.isEmpty()) return adbNote("Type the IP address (127.0.0.1 works on this headset).")
        if (port == null) return adbNote("Type the pairing port (numbers only).")
        if (code.length < 4) return adbNote("Type the 6-digit pairing code.")
        if (_adb.value.busy) return adbNote("Still working. Wait a few seconds, then try again.")
        viewModelScope.launch {
            adbMutex.withLock {
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
    }

    fun adbConnect(host: String, port: Int?) {
        if (host.isEmpty()) return adbNote("Type the IP address (127.0.0.1 works on this headset).")
        if (_adb.value.busy) return adbNote("Still working. Wait a few seconds, then try again.")
        viewModelScope.launch {
            adbMutex.withLock {
                _adb.value = _adb.value.copy(busy = true, message = "Connecting…")
                val result = withContext(Dispatchers.IO) {
                    if (port == null) adbController.autoConnect()
                    else adbController.connectManual(host, port, 10_000L)
                }
                _adb.value = result.fold(
                    onSuccess = { _adb.value.copy(connected = true, busy = false, message = "Connected") },
                    onFailure = {
                        _adb.value.copy(
                            connected = false,
                            busy = false,
                            message = "Connect failed: ${it.friendly()}",
                        )
                    },
                )
            }
        }
    }

    fun adbDisconnect() {
        viewModelScope.launch {
            adbMutex.withLock {
                withContext(Dispatchers.IO) { adbController.disconnect() }
                _adb.value = _adb.value.copy(connected = false, busy = false, message = "Disconnected")
            }
        }
    }

    private fun Throwable.friendly(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun toast(message: String) {
        Toast.makeText(getApplication<Application>(), message, Toast.LENGTH_SHORT).show()
    }
}
