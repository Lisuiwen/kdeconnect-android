/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Lite Remote contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.kde.kdeconnect_tp.R

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LiteRemoteScreen(
    model: LiteRemoteViewModel,
    voice: VoiceInputState,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onRefresh: () -> Unit,
    onFullApp: () -> Unit,
    onAppSettings: () -> Unit,
    onVoiceMode: (Boolean) -> Unit,
) {
    var settings by remember { mutableStateOf(false) }
    var devicePicker by remember { mutableStateOf(false) }
    val active = voice.phase == VoiceInputPhase.STARTING || voice.phase == VoiceInputPhase.LISTENING
    val busy = active || voice.phase == VoiceInputPhase.PROCESSING

    Scaffold(
        modifier = Modifier.safeDrawingPadding().imePadding(),
        topBar = {
            TopAppBar(title = {
                Column {
                    Text(model.selected?.name ?: stringResource(R.string.lite_choose_device), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(when {
                        model.selected?.connected == true -> R.string.lite_connected
                        model.refreshing -> R.string.lite_connecting
                        else -> R.string.lite_disconnected
                    }), style = MaterialTheme.typography.labelMedium,
                        color = if (model.selected?.connected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }, actions = {
                TextButton(onClick = { devicePicker = true }) { Text(stringResource(R.string.lite_switch_device)) }
                Box {
                    IconButton(onClick = { settings = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.lite_settings))
                    }
                    DropdownMenu(expanded = settings, onDismissRequest = { settings = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.lite_full_app)) }, onClick = { settings = false; onFullApp() })
                        DropdownMenuItem(text = { Text(stringResource(if (model.defaultHome) R.string.lite_default_on else R.string.lite_default_off)) },
                            onClick = { model.updateDefaultHome(!model.defaultHome); settings = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lite_reconnect)) }, onClick = { settings = false; onRefresh() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lite_app_permissions)) }, onClick = { settings = false; onAppSettings() })
                    }
                }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (model.selected == null) {
                Text(stringResource(R.string.lite_pair_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onFullApp) { Text(stringResource(R.string.lite_pair_device)) }
            } else if (!model.canSend) {
                Text(stringResource(if (model.selected?.connected == true) R.string.lite_keyboard_unavailable else R.string.lite_disconnected_hint),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            MicrophoneButton(voice, model.holdToTalk, onStartVoice, onStopVoice, onCancelVoice)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(when (voice.phase) {
                VoiceInputPhase.STARTING -> R.string.lite_voice_starting
                VoiceInputPhase.LISTENING -> R.string.lite_voice_listening
                VoiceInputPhase.PROCESSING -> R.string.lite_voice_processing
                VoiceInputPhase.ERROR -> voiceErrorMessage(voice.error)
                VoiceInputPhase.IDLE -> if (model.holdToTalk) R.string.lite_hold_to_talk else R.string.lite_tap_to_talk
            }), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                color = if (voice.phase == VoiceInputPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (busy) {
                val seconds = voice.elapsedMillis / 1000
                Text("%02d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp))
                if (active) LinearProgressIndicator(progress = { ((voice.rmsDb + 2) / 12).coerceIn(0f, 1f) },
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.35f))
                if (voice.partialText.isNotBlank()) Text(voice.partialText, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            }
            TextButton(enabled = !busy, onClick = { onVoiceMode(!model.holdToTalk) }) {
                Text(stringResource(if (model.holdToTalk) R.string.lite_use_tap_mode else R.string.lite_use_hold_mode))
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = model.draft, onValueChange = model::editDraft,
                label = { Text(stringResource(R.string.lite_draft)) },
                placeholder = { Text(stringResource(R.string.lite_draft_hint)) },
                minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = model.draft.isNotEmpty() && !busy, onClick = { model.editDraft("") }) {
                    Text(stringResource(R.string.clear_compose))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = model.canSend && model.draft.isNotBlank() && !busy, onClick = model::sendText) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.lite_send_text), modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(enabled = model.canSend && !busy,
                        onClick = { model.sendShortcut(RemoteShortcutController.Shortcut.ENTER) }) {
                        Text(stringResource(R.string.lite_enter))
                    }
                }
            }
            model.notice?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
            }
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            val shortcuts = listOf(
                Triple(RemoteShortcutController.Shortcut.SCREENSHOT, R.string.lite_screenshot, "Win ⇧ S"),
                Triple(RemoteShortcutController.Shortcut.COPY, R.string.lite_copy, "Ctrl C"),
                Triple(RemoteShortcutController.Shortcut.PASTE, R.string.lite_paste, "Ctrl V"),
                Triple(RemoteShortcutController.Shortcut.ESCAPE, R.string.lite_escape, "Esc"),
                Triple(RemoteShortcutController.Shortcut.UNDO, R.string.lite_undo, "Ctrl Z"),
            )
            shortcuts.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (shortcut, label, keys) ->
                        OutlinedButton(onClick = { model.sendShortcut(shortcut) }, enabled = model.canSend && !busy,
                            modifier = Modifier.weight(1f).height(64.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(label), maxLines = 1)
                                Text(keys, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.lite_screenshot_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
    if (devicePicker) {
        AlertDialog(onDismissRequest = { devicePicker = false }, title = { Text(stringResource(R.string.lite_choose_device)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (model.devices.isEmpty()) Text(stringResource(R.string.lite_pair_hint))
                    model.devices.forEach { device ->
                        TextButton(onClick = { onSelectDevice(device.id); devicePicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (device.id == model.selectedId) "✓ " else ""}${device.name} · " + stringResource(
                                if (device.connected) R.string.lite_connected else R.string.lite_disconnected))
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { devicePicker = false; onFullApp() }) { Text(stringResource(R.string.lite_pair_device)) } },
            dismissButton = { TextButton(onClick = { devicePicker = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}

@Composable
private fun MicrophoneButton(voice: VoiceInputState, holdToTalk: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onCancel: () -> Unit) {
    val currentVoice by rememberUpdatedState(voice)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    val active = voice.phase == VoiceInputPhase.LISTENING || voice.phase == VoiceInputPhase.STARTING
    val description = stringResource(if (active) R.string.lite_stop_voice else if (holdToTalk) R.string.lite_hold_to_talk else R.string.lite_tap_to_talk)
    Surface(shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(128.dp).semantics {
            role = Role.Button
            contentDescription = description
            // Accessibility services use a toggle, since they cannot emulate a held finger reliably.
            onClick {
                if (currentVoice.phase == VoiceInputPhase.STARTING || currentVoice.phase == VoiceInputPhase.LISTENING) stop() else start()
                true
            }
        }.pointerInput(holdToTalk) {
            detectTapGestures(onPress = {
                if (holdToTalk) {
                    if (currentVoice.phase != VoiceInputPhase.PROCESSING) {
                        start()
                        if (tryAwaitRelease()) stop() else cancel()
                    }
                }
            }, onTap = {
                if (!holdToTalk && currentVoice.phase != VoiceInputPhase.PROCESSING) {
                    if (currentVoice.phase == VoiceInputPhase.STARTING || currentVoice.phase == VoiceInputPhase.LISTENING) stop() else start()
                }
            })
        }) {
        Box(contentAlignment = Alignment.Center) {
            if (voice.phase == VoiceInputPhase.PROCESSING) CircularProgressIndicator(Modifier.size(48.dp))
            else Icon(painterResource(R.drawable.ic_mic_black), contentDescription = null, modifier = Modifier.size(52.dp))
        }
    }
}

private fun voiceErrorMessage(error: VoiceInputError?): Int = when (error) {
    VoiceInputError.PERMISSION_REQUIRED -> R.string.lite_mic_permission
    VoiceInputError.SERVICE_UNAVAILABLE -> R.string.lite_voice_unavailable
    VoiceInputError.NO_MATCH, VoiceInputError.SPEECH_TIMEOUT -> R.string.lite_voice_no_match
    VoiceInputError.NETWORK, VoiceInputError.NETWORK_TIMEOUT -> R.string.lite_voice_network
    VoiceInputError.LANGUAGE_NOT_SUPPORTED, VoiceInputError.LANGUAGE_UNAVAILABLE -> R.string.lite_voice_language
    VoiceInputError.AUDIO -> R.string.lite_voice_audio
    VoiceInputError.BUSY, VoiceInputError.TOO_MANY_REQUESTS -> R.string.lite_voice_busy
    else -> R.string.lite_voice_failed
}
