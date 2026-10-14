package com.liamo.f1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private val TIME = DateTimeFormatter.ofPattern("d MMM HH:mm")

fun formatTime(epochMs: Long?): String =
    epochMs?.let { TIME.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "never"

fun ago(epochMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (epochMs == null) return "never"
    val min = (now - epochMs) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / 1440} days ago"
    }
}
