package com.liamo.f1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liamo.f1.F1App
import com.liamo.f1.ble.WatchSession
import com.liamo.f1.protocol.Commands
import com.liamo.f1.protocol.Measure
import com.liamo.f1.protocol.WatchEvent
import com.liamo.f1.protocol.hexToBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Whether the debug screen holds the connection open (survives leaving the tab). */
private object DebugHold {
    val held = MutableStateFlow(false)
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * Phase 2 proving ground: Connect, Battery, Vibrate, Live HR and a scrolling log of raw
 * hex in and out, like the laptop's `listen`. Everything here also logs attempts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(app: F1App, modifier: Modifier = Modifier) {
    val session = app.session
    val status by session.status.collectAsStateWithLifecycle()
    val held by DebugHold.held.collectAsStateWithLifecycle()
    val log by session.log.collectAsStateWithLifecycle()
    var lastEvent by remember { mutableStateOf("–") }
    var raw by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        session.events.collect { e ->
            lastEvent = when (e) {
                is WatchEvent.Battery -> "Battery ${e.percent}%${if (e.charging) " (charging)" else ""}"
                is WatchEvent.DeviceInfo -> "Firmware ${e.firmware}, model ${e.model}"
                is WatchEvent.HeartRate -> "Heart rate ${e.bpm} bpm"
                is WatchEvent.SpO2 -> "SpO2 ${e.percent}%"
                is WatchEvent.BloodPressure -> "BP ${e.value1} / ${e.value2} (order unverified)"
                is WatchEvent.Measuring -> "Measuring…"
                else -> e.toString()
            }
        }
    }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    fun act(block: suspend () -> Unit) {
        app.scope.launch {
            try {
                session.withConnection { block() }
            } catch (e: Exception) {
                session.note('·', "error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Status: ${status.name.lowercase()}", style = MaterialTheme.typography.titleMedium)
                Text(lastEvent, style = MaterialTheme.typography.bodyMedium)
            }
            if (!held) {
                Button(onClick = {
                    app.scope.launch {
                        try {
                            session.acquire()
                            DebugHold.held.value = true
                            app.ops.onConnected()
                        } catch (e: Exception) {
                            session.note('·', "error: ${e.message}")
                        }
                    }
                }) { Text("Connect") }
            } else {
                OutlinedButton(onClick = {
                    DebugHold.held.value = false
                    app.scope.launch { session.release() }
                }) { Text("Disconnect") }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { act { app.ops.readBattery() } }) { Text("Battery") }
            FilledTonalButton(onClick = { act { app.ops.readInfo() } }) { Text("Info") }
            FilledTonalButton(onClick = { act { app.ops.vibrate() } }) { Text("Vibrate") }
            FilledTonalButton(onClick = { act { app.ops.readSteps() } }) { Text("Steps") }
            FilledTonalButton(onClick = { act { app.ops.setClockIfNeeded(force = true) } }) { Text("Set clock") }
            // Raw live HR needs the connection held open, or it would close right after sending.
            FilledTonalButton(
                onClick = { act { session.send(Commands.measure(Measure.LIVE_HR, true)) } },
                enabled = held && status == WatchSession.Status.READY,
            ) { Text("Live HR on") }
            FilledTonalButton(
                onClick = { act { session.send(Commands.measure(Measure.LIVE_HR, false)) } },
                enabled = held && status == WatchSession.Status.READY,
            ) { Text("Live HR off") }
            OutlinedButton(onClick = { act { app.ops.spotSpO2() } }) { Text("SpO2 ⚗") }
            OutlinedButton(onClick = { act { app.ops.spotBloodPressure() } }) { Text("BP ⚗") }
            OutlinedButton(onClick = { act { app.ops.syncHistory() } }) { Text("History ⚗") }
            OutlinedButton(onClick = { act { app.ops.notify("Hello from Android") } }) { Text("Notify ⚗") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                label = { Text("Raw frame (hex)") },
                placeholder = { Text("ab0003ff7180") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val bytes = runCatching { hexToBytes(raw) }.getOrNull()
                if (bytes == null || bytes.isEmpty()) session.note('·', "not valid hex: $raw") else act { session.send(bytes) }
            }) { Text("Send") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log (${log.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { session.clearLog() }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(log) { line ->
                Text(
                    "${CLOCK.format(Instant.ofEpochMilli(line.time))} ${line.dir} ${line.text}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = when (line.dir) {
                        '>' -> MaterialTheme.colorScheme.primary
                        '<' -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
