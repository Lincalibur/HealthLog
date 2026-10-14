package com.liamo.f1.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liamo.f1.F1App
import com.liamo.f1.ble.Pairing
import com.liamo.f1.sync.GitHubSync
import com.liamo.f1.sync.Schedules

@Composable
fun SettingsScreen(app: F1App, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val s by app.settings.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val address = Pairing.addressFrom(result.data)
        if (address != null) {
            app.settings.setWatchAddress(address)
            Schedules.apply(context, app.settings.state.value.schedulesOn)
            message = "Watch paired."
        } else {
            message = "Pairing cancelled."
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Section("Watch") {
            Text(
                if (s.watchAddress == null) {
                    "Not paired. Close Wearfit (or uninstall it) and the laptop tools first: the watch accepts one connection at a time."
                } else {
                    "Paired. The address stays on this phone and is never uploaded."
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    Pairing.start(
                        context,
                        onChooser = { chooser.launch(IntentSenderRequest.Builder(it).build()) },
                        onError = { message = it },
                    )
                }) { Text(if (s.watchAddress == null) "Pair watch" else "Pair again") }
                if (s.watchAddress != null) {
                    OutlinedButton(onClick = {
                        app.settings.setWatchAddress(null)
                        Schedules.apply(context, false)
                    }) { Text("Forget") }
                }
            }
        }

        GitHubSection(app, s.owner, s.repo, s.hasToken) { message = it }

        Section("Schedule") {
            ToggleRow("Snapshot every 2 h, sync every 6 h", s.schedulesOn) {
                app.settings.setSchedulesOn(it)
                Schedules.apply(context, it && s.watchAddress != null)
            }
            ToggleRow("Include experimental SpO2 + history in snapshots", s.experimentalInSnapshot) {
                app.settings.setExperimentalInSnapshot(it)
            }
            BatteryExemption()
        }

        Section("Privacy") {
            Text(
                "Never collected: location (the app has no location permission), contacts, phone notifications. " +
                    "Never uploaded: the watch's or phone's Bluetooth address, serial numbers, the GitHub token. " +
                    "Public by design: UTC timestamps and values of heart rate, steps, SpO2, blood pressure and battery, " +
                    "plus how each attempt went.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { uri.openUri("https://github.com/settings/personal-access-tokens") }) {
                Text("Create a fine-grained GitHub token")
            }
        }
    }
}

@Composable
private fun GitHubSection(app: F1App, owner: String, repo: String, hasToken: Boolean, onMessage: (String) -> Unit) {
    var o by remember(owner) { mutableStateOf(owner) }
    var r by remember(repo) { mutableStateOf(repo) }
    var token by remember { mutableStateOf("") }
    Section("GitHub") {
        Text(
            "Fine-grained token for this one repo only: Contents and Pull requests, read and write. " +
                "It is stored encrypted on this phone. Data goes to branch ${GitHubSync.BRANCH} as a pull request.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(o, { o = it }, label = { Text("Owner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(r, { r = it }, label = { Text("Repository") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            token, { token = it },
            label = { Text(if (hasToken) "Token (saved; paste to replace)" else "Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                app.settings.setGitHub(o, r, token)
                token = ""
                onMessage("GitHub settings saved.")
            }) { Text("Save") }
            if (hasToken) OutlinedButton(onClick = { app.settings.clearToken() }) { Text("Remove token") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Phone makers' battery savers often kill background work; ask to be exempt. */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryExemption() {
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) {
        Text("Battery optimisation: exempt", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        Text("Battery optimisation may delay snapshots.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        }) { Text("Allow background work") }
    }
}
