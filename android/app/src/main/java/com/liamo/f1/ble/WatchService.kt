package com.liamo.f1.ble

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.liamo.f1.F1App
import com.liamo.f1.R
import com.liamo.f1.data.AttemptKind
import com.liamo.f1.data.Outcome
import com.liamo.f1.data.Source
import com.liamo.f1.protocol.Commands
import com.liamo.f1.protocol.Measure
import com.liamo.f1.protocol.WatchEvent
import com.liamo.f1.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (type connectedDevice) for live heart rate. Sends `31 0A 01`,
 * stores each reply (one row per 30 s), and sends `31 0A 00` when stopped or after
 * [LIMIT_MS] to protect the watch battery.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        if (job?.isActive != true) job = scope.launch { runLiveHeartRate() }
        return START_NOT_STICKY
    }

    private suspend fun runLiveHeartRate() {
        val app = application as F1App
        val started = System.currentTimeMillis()
        var values = 0
        var connected = false
        _running.value = true
        try {
            app.session.withConnection {
                connected = true
                app.ops.onConnected()
                try {
                    withTimeoutOrNull(LIMIT_MS) {
                        events
                            .onSubscription { send(Commands.measure(Measure.LIVE_HR, true)) }
                            .filterIsInstance<WatchEvent.HeartRate>()
                            .collect { hr ->
                                _bpm.value = hr.bpm
                                if (app.store.heartRate(hr.bpm, Source.LIVE) == Outcome.OK) values++
                                update(hr.bpm)
                            }
                    }
                } finally {
                    withContext(NonCancellable) { runCatching { send(Commands.measure(Measure.LIVE_HR, false)) } }
                }
            }
        } catch (e: ConnectFailed) {
            _error.value = e.message
        } finally {
            withContext(NonCancellable) {
                // A failed connect is already logged as a `connect` attempt.
                if (connected) {
                    app.store.attempt(AttemptKind.HR, if (values > 0) Outcome.OK else Outcome.NO_REPLY, started, "live values=$values")
                }
            }
            _running.value = false
            _bpm.value = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun update(bpm: Int) {
        // Without the notification permission (Android 13+) the service still runs; it just can't show the value.
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(bpm))
    }

    private fun notification(bpm: Int?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, F1App.CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle("Live heart rate")
            .setContentText(bpm?.let { "$it bpm" } ?: "Connecting to the watch…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val LIMIT_MS = 10 * 60_000L
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.liamo.f1.STOP_LIVE_HR"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _bpm = MutableStateFlow<Int?>(null)
        val bpm: StateFlow<Int?> = _bpm.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        fun start(context: Context) {
            _error.value = null
            context.startForegroundService(Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }
    }
}
