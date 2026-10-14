package com.liamo.f1

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.liamo.f1.ble.WatchOps
import com.liamo.f1.ble.WatchSession
import com.liamo.f1.data.AppDatabase
import com.liamo.f1.data.Settings
import com.liamo.f1.data.Store
import com.liamo.f1.sync.Schedules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app-wide singletons (a hand-rolled container; the app is small). */
class F1App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db by lazy { AppDatabase.create(this) }
    val settings by lazy { Settings(this) }
    val store by lazy { Store(db) }
    val session by lazy { WatchSession(this, store, settings, scope) }
    val ops by lazy { WatchOps(session, store) }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "Watch connection", NotificationManager.IMPORTANCE_LOW),
        )
        if (settings.state.value.watchAddress != null) Schedules.apply(this, settings.state.value.schedulesOn)
    }

    companion object {
        const val CHANNEL_WATCH = "watch"
    }
}
