package com.knowyourcase.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.knowyourcase.app.data.local.AppDatabase
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).historyDao()
    val history = dao.getAll().asLiveData()

    fun clearAll() = viewModelScope.launch { dao.clearAll() }
}
