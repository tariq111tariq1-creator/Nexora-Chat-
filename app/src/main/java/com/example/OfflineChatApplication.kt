package com.example

import android.app.Application
import com.example.data.local.OfflineChatDatabase
import com.example.data.local.UserPreferencesRepository
import com.example.data.repository.MeshChatRepository
import com.example.notifications.NotificationOrchestrator

class OfflineChatApplication : Application() {

    lateinit var database: OfflineChatDatabase
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    lateinit var repository: MeshChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationOrchestrator.ensureChannelsCreated(this)
        database = OfflineChatDatabase.getInstance(this)
        preferencesRepository = UserPreferencesRepository(this)
        repository = MeshChatRepository(
            context = this,
            db = database,
            preferencesRepository = preferencesRepository
        )
    }
}
