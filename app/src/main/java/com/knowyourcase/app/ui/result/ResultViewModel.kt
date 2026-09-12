package com.knowyourcase.app.ui.result

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knowyourcase.app.data.api.CaseResponse
import com.knowyourcase.app.data.local.AppDatabase
import com.knowyourcase.app.data.local.CaseCache
import com.knowyourcase.app.data.local.HistoryEntity
import kotlinx.coroutines.launch

sealed class ResultState {
    object Loading : ResultState()
    data class Success(val data: CaseResponse) : ResultState()
    data class Error(val message: String) : ResultState()
}

class ResultViewModel : ViewModel() {

    val state = MutableLiveData<ResultState>(ResultState.Loading)

    fun startLoading() {
        state.value = ResultState.Loading
    }

    fun accept(data: CaseResponse, context: Context) {
        state.value = ResultState.Success(data)
        viewModelScope.launch {
            CaseCache.put(context, data)
            AppDatabase.getInstance(context).historyDao().insert(
                HistoryEntity(
                    cnr = data.cnr,
                    caseTitle = data.caseTitle ?: data.cnr
                )
            )
        }
    }

    fun loadCached(cnr: String, context: Context): Boolean {
        val cached = CaseCache.get(context, cnr) ?: return false
        state.value = ResultState.Success(cached)
        viewModelScope.launch {
            AppDatabase.getInstance(context).historyDao().insert(
                HistoryEntity(cnr = cached.cnr, caseTitle = cached.caseTitle ?: cached.cnr)
            )
        }
        return true
    }

    fun fail(message: String) {
        state.value = ResultState.Error(message)
    }
}
