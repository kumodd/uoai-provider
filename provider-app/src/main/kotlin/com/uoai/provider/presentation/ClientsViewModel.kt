package com.uoai.provider.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uoai.clients.ClientInfo
import com.uoai.clients.ClientPermissions
import com.uoai.clients.ClientRepository
import com.uoai.core.database.AppDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClientRow(val client: ClientInfo, val total: Int, val failed: Int)

@HiltViewModel
class ClientsViewModel @Inject constructor(private val repo: ClientRepository, db: AppDatabase) : ViewModel() {
    val clients: StateFlow<List<ClientRow>> = combine(repo.observeClients(), db.requestDao().observeUsage()) { cs, usage ->
        val byId = usage.associateBy { it.clientId }
        cs.map { ClientRow(it, byId[it.id]?.total ?: 0, byId[it.id]?.failed ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun approve(c: ClientInfo) = viewModelScope.launch { repo.approve(c.id) }
    fun reject(c: ClientInfo) = viewModelScope.launch { repo.reject(c.id) }
    fun revoke(c: ClientInfo) = viewModelScope.launch { repo.revoke(c.id) }
    fun setEnabled(c: ClientInfo, on: Boolean) = viewModelScope.launch { repo.setEnabled(c.id, on) }
    fun permissions(c: ClientInfo, p: ClientPermissions) = viewModelScope.launch { repo.updatePermissions(c.id, p) }
}
