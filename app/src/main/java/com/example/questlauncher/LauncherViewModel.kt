
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

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _refreshResult = MutableStateFlow<RefreshResult?>(null)
    val refreshResult: StateFlow<RefreshResult?> = _refreshResult

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

    fun setRefreshRate(hz: Int) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repo.setRefreshRate(hz) }
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

    private fun toast(message: String) {
        Toast.makeText(getApplication<Application>(), message, Toast.LENGTH_SHORT).show()
    }
}
