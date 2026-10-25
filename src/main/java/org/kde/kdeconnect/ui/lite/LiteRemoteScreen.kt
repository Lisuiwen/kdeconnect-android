/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Lite Remote contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kde.kdeconnect_tp.R

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
    onSendFiles: () -> Unit,
    onVoiceMode: (Boolean) -> Unit,
) {
    var settings by remember { mutableStateOf(false) }
    var devicePicker by remember { mutableStateOf(false) }
    val palette = litePalette(isSystemInDarkTheme())
    val active = voice.phase == VoiceInputPhase.STARTING || voice.phase == VoiceInputPhase.LISTENING
    val busy = active || voice.phase == VoiceInputPhase.PROCESSING || model.loadingAttachments || model.sending
    val connected = model.selected?.connected == true
    val microphoneTopPadding = if (LocalConfiguration.current.screenHeightDp < 640) 48.dp else 96.dp
    val connectionStatus = stringResource(when {
        connected -> R.string.lite_connected
        model.refreshing -> R.string.lite_connecting
        else -> R.string.lite_disconnected
    })

    Scaffold(
        modifier = Modifier.background(palette.background).safeDrawingPadding().imePadding(),
        containerColor = palette.background,
        contentColor = palette.text,
        topBar = {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { devicePicker = true }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_device_desktop_32dp), contentDescription = null,
                        tint = if (connected) palette.connected else palette.secondary, modifier = Modifier.size(18.dp))
                    Text("${model.selected?.name ?: stringResource(R.string.lite_choose_device)} · $connectionStatus",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { settings = true }, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_settings_24dp), contentDescription = stringResource(R.string.lite_settings),
                            tint = palette.secondary, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = settings, onDismissRequest = { settings = false }) {
                        DropdownMenuItem(enabled = !busy,
                            text = { Text(stringResource(if (model.holdToTalk) R.string.lite_use_tap_mode else R.string.lite_use_hold_mode)) },
                            onClick = { settings = false; onVoiceMode(!model.holdToTalk) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lite_reconnect)) },
                            onClick = { settings = false; onRefresh() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lite_app_permissions)) },
                            onClick = { settings = false; onAppSettings() })
                        DropdownMenuItem(text = { Text(stringResource(if (model.defaultHome) R.string.lite_default_on else R.string.lite_default_off)) },
                            onClick = { model.updateDefaultHome(!model.defaultHome); settings = false })
                        HorizontalDivider(color = palette.border)
                        DropdownMenuItem(text = { Text(stringResource(R.string.lite_full_app)) },
                            onClick = { settings = false; onFullApp() })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (model.selected == null) {
                Text(stringResource(R.string.lite_pair_hint), color = palette.secondary,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            } else if (!model.canSend) {
                Text(stringResource(if (connected) R.string.lite_keyboard_unavailable else R.string.lite_disconnected_hint),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(microphoneTopPadding))
            MicrophoneButton(voice, model.holdToTalk, palette, !model.loadingAttachments && !model.sending,
                onStartVoice, onStopVoice, onCancelVoice)
            Spacer(Modifier.height(28.dp))
            val draftLabel = stringResource(R.string.lite_draft)
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                color = palette.panel, contentColor = palette.text) {
                Column(Modifier.padding(12.dp)) {
                    Text(draftLabel, color = palette.secondary, style = MaterialTheme.typography.labelMedium)
                    BasicTextField(value = model.draft, onValueChange = model::editDraft,
                        enabled = !model.loadingAttachments && !model.sending,
                        minLines = 2, maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text),
                        cursorBrush = SolidColor(palette.text),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp)
                            .semantics { contentDescription = draftLabel },
                        decorationBox = { editor ->
                            Box {
                                if (model.draft.isEmpty()) Text(stringResource(R.string.lite_draft_hint),
                                    color = palette.secondary, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                editor()
                            }
                        })
                    if (model.attachments.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            model.attachments.forEach { attachment ->
                                AttachmentPreview(attachment, palette, enabled = !busy,
                                    onRemove = { model.removeAttachment(attachment.uri) })
                            }
                        }
                    }
                    if (model.loadingAttachments || model.sending) {
                        Text(stringResource(if (model.loadingAttachments) R.string.lite_adding_files else R.string.lite_sending_contents),
                            color = palette.secondary, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp))
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compactActions = maxWidth < 320.dp || LocalConfiguration.current.fontScale > 1.3f
                        val sendStyle = if (compactActions) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (compactActions) {
                                IconButton(enabled = !busy && model.selected != null, onClick = onSendFiles,
                                    modifier = Modifier.size(48.dp)) {
                                    Icon(painterResource(R.drawable.share_plugin_action_24dp),
                                        contentDescription = stringResource(R.string.lite_send_files), modifier = Modifier.size(18.dp))
                                }
                                IconButton(enabled = model.hasDraft && !busy, onClick = model::clearDraft,
                                    modifier = Modifier.size(48.dp)) {
                                    Icon(painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.lite_clear), modifier = Modifier.size(18.dp))
                                }
                                IconButton(enabled = model.canSendDraft && !busy, onClick = model::sendText,
                                    modifier = Modifier.size(48.dp)) {
                                    Icon(painterResource(R.drawable.ic_baseline_send_24),
                                        contentDescription = stringResource(R.string.lite_send_text), modifier = Modifier.size(18.dp))
                                }
                            } else {
                                TextButton(enabled = !busy && model.selected != null, onClick = onSendFiles,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = palette.secondary),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                                    Icon(painterResource(R.drawable.share_plugin_action_24dp), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.lite_send_files), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                                }
                                TextButton(enabled = model.hasDraft && !busy, onClick = model::clearDraft,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = palette.secondary),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.lite_clear), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                                }
                                TextButton(enabled = model.canSendDraft && !busy, onClick = model::sendText,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = palette.secondary),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                                    Icon(painterResource(R.drawable.ic_baseline_send_24), contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.lite_send_text), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                                }
                            }
                            }
                            Spacer(Modifier.weight(1f))
                            Button(enabled = model.canSendDraftAndEnter && !busy, onClick = model::sendTextAndEnter,
                                modifier = Modifier.widthIn(min = 80.dp).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.text, contentColor = palette.background,
                                    disabledContainerColor = palette.button, disabledContentColor = palette.secondary.copy(alpha = 0.65f))) {
                                Text(stringResource(R.string.lite_send_text_enter), style = sendStyle, maxLines = 2, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            model.notice?.let {
                Text(stringResource(it), color = palette.secondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            val shortcuts = listOf(
                ShortcutItem(RemoteShortcutController.Shortcut.SCREENSHOT, R.string.lite_screenshot, R.drawable.ic_lite_screenshot_24dp),
                ShortcutItem(RemoteShortcutController.Shortcut.COPY, R.string.lite_copy, R.drawable.ic_lite_copy_24dp),
                ShortcutItem(RemoteShortcutController.Shortcut.PASTE, R.string.lite_paste, R.drawable.ic_baseline_content_paste_24),
                ShortcutItem(RemoteShortcutController.Shortcut.ENTER, R.string.lite_enter, R.drawable.ic_keyboard_return_black_24dp),
                ShortcutItem(RemoteShortcutController.Shortcut.ESCAPE, R.string.lite_escape, R.drawable.ic_close),
                ShortcutItem(RemoteShortcutController.Shortcut.UNDO, R.string.lite_undo, R.drawable.ic_lite_undo_24dp),
            )
            shortcuts.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        ShortcutTile(item, palette, Modifier.weight(1f),
                            enabled = model.canSend && !busy,
                            onClick = { model.sendShortcut(item.shortcut) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
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

private data class ShortcutItem(
    val shortcut: RemoteShortcutController.Shortcut,
    val label: Int,
    val icon: Int,
)

@Composable
private fun AttachmentPreview(attachment: LiteAttachment, palette: LitePalette, enabled: Boolean, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = palette.button, contentColor = palette.text) {
        Row(Modifier.widthIn(max = 240.dp).heightIn(min = 48.dp).padding(start = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (attachment.mimeType?.startsWith("image/") == true)
                R.drawable.ic_action_image_edit_24dp else R.drawable.share_plugin_action_24dp),
                contentDescription = null, modifier = Modifier.size(18.dp))
            Text(attachment.name, modifier = Modifier.widthIn(max = 140.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.lite_remove_attachment, attachment.name),
                    tint = if (enabled) palette.secondary else palette.secondary.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ShortcutTile(item: ShortcutItem, palette: LitePalette, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, palette.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text,
            disabledContentColor = palette.secondary.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(item.icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(item.label), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MicrophoneButton(voice: VoiceInputState, holdToTalk: Boolean, palette: LitePalette, enabled: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onCancel: () -> Unit) {
    val currentVoice by rememberUpdatedState(voice)
    val currentEnabled by rememberUpdatedState(enabled)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val cancel by rememberUpdatedState(onCancel)
    val active = voice.phase == VoiceInputPhase.LISTENING || voice.phase == VoiceInputPhase.STARTING
    val busy = active || voice.phase == VoiceInputPhase.PROCESSING
    val description = stringResource(if (active) R.string.lite_stop_voice else if (holdToTalk) R.string.lite_hold_to_talk else R.string.lite_tap_to_talk)
    val status = stringResource(when (voice.phase) {
        VoiceInputPhase.STARTING -> R.string.lite_voice_starting
        VoiceInputPhase.LISTENING -> R.string.lite_voice_listening
        VoiceInputPhase.PROCESSING -> R.string.lite_voice_processing
        VoiceInputPhase.ERROR -> voiceErrorMessage(voice.error)
        VoiceInputPhase.IDLE -> if (holdToTalk) R.string.lite_hold_to_talk else R.string.lite_tap_to_talk
    })
    // One touch region includes both the circle and its labels; drawing and hit bounds stay aligned.
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = description
            stateDescription = status
            if (!enabled || voice.phase == VoiceInputPhase.PROCESSING) disabled()
            // Accessibility services use a toggle, since they cannot emulate a held finger reliably.
            onClick {
                if (!currentEnabled || currentVoice.phase == VoiceInputPhase.PROCESSING) {
                    false
                } else {
                    if (currentVoice.phase == VoiceInputPhase.STARTING || currentVoice.phase == VoiceInputPhase.LISTENING) stop() else start()
                    true
                }
            }
        }.pointerInput(holdToTalk) {
            detectTapGestures(onPress = {
                if (holdToTalk && currentEnabled) {
                    if (currentVoice.phase != VoiceInputPhase.PROCESSING) {
                        start()
                        if (tryAwaitRelease()) stop() else cancel()
                    }
                }
            }, onTap = {
                if (!holdToTalk && currentEnabled && currentVoice.phase != VoiceInputPhase.PROCESSING) {
                    if (currentVoice.phase == VoiceInputPhase.STARTING || currentVoice.phase == VoiceInputPhase.LISTENING) stop() else start()
                }
            })
        }) {
        Surface(shape = CircleShape, color = if (active) palette.button else palette.panel, modifier = Modifier.size(128.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (voice.phase == VoiceInputPhase.PROCESSING) CircularProgressIndicator(Modifier.size(32.dp), color = palette.text, strokeWidth = 2.dp)
                else Icon(painterResource(R.drawable.ic_mic_black), contentDescription = null, tint = palette.text, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(status, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (voice.phase == VoiceInputPhase.ERROR) MaterialTheme.colorScheme.error else palette.text)
        if (voice.phase == VoiceInputPhase.IDLE || active) {
            Text(stringResource(if (holdToTalk) R.string.lite_release_to_recognize else R.string.lite_tap_to_recognize),
                color = palette.secondary, style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        if (busy) {
            val seconds = voice.elapsedMillis / 1000
            Text("%02d:%02d".format(seconds / 60, seconds % 60), color = palette.secondary,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            if (active) LinearProgressIndicator(progress = { ((voice.rmsDb + 2) / 12).coerceIn(0f, 1f) },
                color = palette.secondary, trackColor = palette.border,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.25f))
            if (voice.partialText.isNotBlank()) Text(voice.partialText, color = palette.secondary,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private data class LitePalette(
    val background: Color,
    val panel: Color,
    val text: Color,
    val secondary: Color,
    val border: Color,
    val button: Color,
    val connected: Color,
)

private fun litePalette(dark: Boolean) = if (dark) LitePalette(
    background = Color(0xFF151517), panel = Color(0xFF242427), text = Color(0xFFEEEEF0),
    secondary = Color(0xFFA2A2A8), border = Color(0xFF3B3B40), button = Color(0xFF38383D), connected = Color(0xFF3DDC84),
) else LitePalette(
    background = Color.White, panel = Color(0xFFF6F6F7), text = Color(0xFF1C1C1E),
    secondary = Color(0xFF77777C), border = Color(0xFFE6E6E9), button = Color(0xFFE9E9EB), connected = Color(0xFF00B84A),
)

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
