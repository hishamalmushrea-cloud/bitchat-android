package com.bitchat.android.ui.calls

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.calls.CallSettingsManager

/**
 * Settings screen for voice call configuration:
 *  - TURN server (URI, username, password)
 *  - Auto-reconnect behavior
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallSettingsScreen(
    settingsManager: CallSettingsManager,
    onNavigateBack: () -> Unit
) {
    var turnEnabled by remember { mutableStateOf(settingsManager.isTurnEnabled) }
    var turnUri by remember { mutableStateOf(settingsManager.turnServerUri) }
    var turnUser by remember { mutableStateOf(settingsManager.turnUsername) }
    var turnPass by remember { mutableStateOf(settingsManager.turnPassword) }
    var autoReconnect by remember { mutableStateOf(settingsManager.isAutoReconnectEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.call_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── TURN Server Section ──

            Text(
                text = stringResource(R.string.call_settings_turn_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.call_settings_turn_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.call_settings_turn_title))
                Switch(
                    checked = turnEnabled,
                    onCheckedChange = { turnEnabled = it }
                )
            }

            if (turnEnabled) {
                OutlinedTextField(
                    value = turnUri,
                    onValueChange = { turnUri = it },
                    label = { Text(stringResource(R.string.call_settings_turn_server)) },
                    placeholder = { Text(stringResource(R.string.call_settings_turn_server_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = turnUser,
                    onValueChange = { turnUser = it },
                    label = { Text(stringResource(R.string.call_settings_turn_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = turnPass,
                    onValueChange = { turnPass = it },
                    label = { Text(stringResource(R.string.call_settings_turn_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Auto-Reconnect Section ──

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.call_settings_reconnect_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.call_settings_reconnect_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoReconnect,
                    onCheckedChange = { autoReconnect = it }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Save Button ──

            Button(
                onClick = {
                    settingsManager.isTurnEnabled = turnEnabled
                    settingsManager.turnServerUri = turnUri
                    settingsManager.turnUsername = turnUser
                    settingsManager.turnPassword = turnPass
                    settingsManager.isAutoReconnectEnabled = autoReconnect
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.call_settings_save))
            }
        }
    }
}
