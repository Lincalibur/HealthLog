package com.liamo.f1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liamo.f1.F1App
import com.liamo.f1.ble.WatchService
import com.liamo.f1.ble.WatchSession
import com.liamo.f1.data.Reading
import com.liamo.f1.data.ReadingType
import com.liamo.f1.data.SyncState
import com.liamo.f1.sync.Schedules
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DashboardScreen(app: F1App, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = app.db.readings()
    val startOfDay = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val battery by dao.latest(ReadingType.BATTERY).collectAsStateWithLifecycle(null)
    val hr by dao.latest(ReadingType.HR).collectAsStateWithLifecycle(null)
    val spo2 by dao.latest(ReadingType.SPO2).collectAsStateWithLifecycle(null)
    val steps by dao.maxStepsSince(startOfDay).collectAsStateWithLifecycle(null)
    val total by dao.count().collectAsStateWithLifecycle(0)
    val pending by dao.unexportedCount().collectAsStateWithLifecycle(0)
    val state by app.db.syncState().observe().collectAsStateWithLifecycle(null)
    val status by app.session.status.collectAsStateWithLifecycle()
    val liveOn by WatchService.running.collectAsStateWithLifecycle()
    val liveBpm by WatchService.bpm.collectAsStateWithLifecycle()
    val liveError by WatchService.error.collectAsStateWithLifecycle()
    val attempts by app.db.attempts().recent(8).collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.state.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("HealthLog", style = MaterialTheme.typography.headlineMedium)
        Text(
            when {
                settings.watchAddress == null -> "No watch paired yet: go to Settings."
                status == WatchSession.Status.READY -> "Connected"
                status == WatchSession.Status.CONNECTING -> "Connecting…"
                else -> "Not connected (it connects for each snapshot, then lets go)"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        WatchBusyHint(state)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Battery", battery?.let { "${it.value1}%" }, battery, Modifier.weight(1f))
            Tile("Steps today", steps?.toString(), null, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Heart rate", hr?.let { "${it.value1} bpm" }, hr, Modifier.weight(1f))
            Tile("SpO2 (experimental)", spo2?.let { "${it.value1}%" }, spo2, Modifier.weight(1f))
        }

        Section("Live heart rate") {
            Text(
                when {
                    liveOn && liveBpm != null -> "$liveBpm bpm"
                    liveOn -> "Waiting for the first value…"
                    liveError != null -> "Couldn't connect: $liveError"
                    else -> "Stops by itself after ${WatchService.LIMIT_MS / 60_000} minutes to save the watch battery."
                },
            )
            if (liveOn) {
                OutlinedButton(onClick = { WatchService.stop(context) }) { Text("Stop") }
            } else {
                Button(onClick = { WatchService.start(context) }, enabled = settings.watchAddress != null) { Text("Start live heart rate") }
            }
        }

        Section("Sync") {
            Text("$total readings stored, $pending waiting for GitHub")
            Text("Last sync: ${ago(state?.lastExport)}", style = MaterialTheme.typography.bodySmall)
            state?.lastExportError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state?.lastPullRequestUrl?.let { url ->
                val uri = LocalUriHandler.current
                TextButton(onClick = { uri.openUri(url) }) { Text("Open the pull request") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Schedules.snapshotNow(context) }, enabled = settings.watchAddress != null) { Text("Snapshot now") }
                Button(onClick = { Schedules.syncNow(context) }, enabled = settings.gitHubReady) { Text("Sync to GitHub") }
            }
        }

        Section("Recent attempts") {
            if (attempts.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            attempts.forEach { a ->
                Text(
                    "${formatTime(a.startedAt)}  ${a.kind}: ${a.outcome.replace('_', ' ')}  (${a.durationMs / 1000.0} s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (a.outcome == "ok") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun WatchBusyHint(state: SyncState?) {
    if ((state?.connectFailures ?: 0) < 3) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Watch busy: is Wearfit or the laptop connected?", style = MaterialTheme.typography.titleSmall)
            Text(
                "The watch accepts one connection at a time. Last error: ${state?.lastConnectError ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String?, reading: Reading?, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value ?: "–", style = MaterialTheme.typography.headlineSmall)
            if (reading != null) Text(ago(reading.takenAt), style = MaterialTheme.typography.bodySmall)
        }
    }
}
