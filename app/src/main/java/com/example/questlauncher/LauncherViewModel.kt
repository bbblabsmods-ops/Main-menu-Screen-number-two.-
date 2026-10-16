package com.example.questlauncher

import android.app.Application
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

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppRepository(application)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

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
}
