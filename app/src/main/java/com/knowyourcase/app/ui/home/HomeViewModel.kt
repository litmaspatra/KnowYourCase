package com.knowyourcase.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import com.knowyourcase.app.data.local.AppDatabase

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).historyDao()
    val recentSearches = dao.getRecent().asLiveData()
}
