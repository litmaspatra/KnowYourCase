package com.bluespeaker.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var serverJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BlueSpeakerApp(::startServer, ::stopServer) }
    }

    private fun startServer(update: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
            return
        }
        if (serverJob?.isActive == true) return
        serverJob = lifecycleScope.launch {
            runCatching { BluetoothAudioServer(this@MainActivity).run(update) }
                .onFailure { update(it.message ?: "Connection failed") }
        }
    }

    private fun stopServer() {
        serverJob?.cancel()
        serverJob = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
}

@Composable
private fun BlueSpeakerApp(start: ((String) -> Unit) -> Unit, stop: () -> Unit) {
    var state by remember { mutableStateOf("Ready") }
    var running by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = dynamicLightColorScheme(androidx.compose.ui.platform.LocalContext.current)) {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 3.dp) {
                            Icon(Icons.Rounded.VolumeUp, null, Modifier.padding(14.dp).size(30.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("BlueSpeaker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Text("Use this phone as your computer speaker", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Card(shape = RoundedCornerShape(28.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            Icon(Icons.Rounded.Bluetooth, null, Modifier.size(42.dp))
                            Text(state, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                            Text(
                                if (running) "Keep this screen open while audio is streaming." else "Pair this phone with your computer, then tap Start.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    running = !running
                                    if (running) start { state = it } else { stop(); state = "Ready" }
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) { Text(if (running) "Stop speaker" else "Start speaker") }
                        }
                    }
                }

                Text("48 kHz stereo • Bluetooth Classic • Local only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
