package com.uoai.provider.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uoai.core.database.AppDatabase
import com.uoai.core.database.AuditLogEntity
import com.uoai.plugins.PluginRegistry
import com.uoai.plugins.models.RegisteredPlugin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val registry: PluginRegistry,
    db: AppDatabase,
) : ViewModel() {
    val plugins: StateFlow<List<RegisteredPlugin>> = registry.observePlugins()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val auditLogs: StateFlow<List<AuditLogEntity>> = db.auditDao().observeRecent(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPluginEnabled(id: String, enabled: Boolean) = viewModelScope.launch { registry.setPluginEnabled(id, enabled) }
    fun setToolEnabled(id: String, enabled: Boolean) = viewModelScope.launch { registry.setToolEnabled(id, enabled) }
}
