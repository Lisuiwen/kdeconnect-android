/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Lite Remote contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect_tp.R
import java.io.IOException

data class LiteDevice(
    val id: String,
    val name: String,
    val connected: Boolean,
    val inputEnabled: Boolean,
    val sharingEnabled: Boolean = false,
)

data class LiteAttachment(val uri: String, val name: String, val mimeType: String? = null, val size: Long? = null)

/** Uses KDE's device registry; the only persistent Lite state is selection, preferences and drafts. */
class LiteRemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("lite_remote", Application.MODE_PRIVATE)
    private val defaults = PreferenceManager.getDefaultSharedPreferences(application)
    private var initialSelectionResolved = false

    var devices by mutableStateOf(emptyList<LiteDevice>())
        private set
    var selectedId by mutableStateOf(prefs.getString("selected_device", null))
        private set
    var draft by mutableStateOf(prefs.getString(draftKey(selectedId), "").orEmpty())
        private set
    var attachments by mutableStateOf(readAttachments(selectedId))
        private set
    var loadingAttachments by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    private var pendingAttachmentLoads = 0
    var defaultHome by mutableStateOf(defaults.getBoolean(DEFAULT_HOME_KEY, true))
        private set
    var holdToTalk by mutableStateOf(prefs.getBoolean("hold_to_talk", true))
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var refreshing by mutableStateOf(false)

    val selected: LiteDevice? get() = devices.firstOrNull { it.id == selectedId }
    val canSend: Boolean get() = selected?.let { it.connected && it.inputEnabled } == true
    val canSendFiles: Boolean get() = selected?.let { it.connected && it.sharingEnabled } == true
    val hasDraft: Boolean get() = draft.isNotEmpty() || attachments.isNotEmpty()
    val canSendDraft: Boolean get() = !loadingAttachments && !sending &&
        (draft.isNotBlank() || attachments.isNotEmpty()) &&
        (draft.isBlank() || canSend) && (attachments.isEmpty() || canSendFiles)
    val canSendDraftAndEnter: Boolean get() = canSendDraft && canSend

    fun refreshDevices() {
        devices = KdeConnect.getInstance().devices.values.filter {
            it.isPaired && it.deviceType in setOf(DeviceType.DESKTOP, DeviceType.LAPTOP)
        }
            .map { device ->
                LiteDevice(device.deviceId, device.name, device.isReachable,
                    device.getPlugin<MousePadPlugin>()?.isKeyboardEnabled == true,
                    device.getPlugin<SharePlugin>() != null)
            }.sortedWith(compareByDescending<LiteDevice> { it.name.equals("Brook", ignoreCase = true) }
                .thenByDescending { it.connected }.thenBy { it.name })
        if (selectedId == null || (!initialSelectionResolved && devices.none { it.id == selectedId })) {
            devices.firstOrNull()?.let { selectDevice(it.id) }
        }
        if (devices.isNotEmpty()) initialSelectionResolved = true
    }

    fun selectDevice(id: String) {
        if (id == selectedId) return
        initialSelectionResolved = true
        val unassignedDraft = if (selectedId == null) draft else ""
        val unassignedAttachments = if (selectedId == null) attachments else emptyList()
        selectedId = id
        prefs.edit { putString("selected_device", id) }
        draft = prefs.getString(draftKey(id), "").orEmpty()
        attachments = readAttachments(id)
        if (unassignedDraft.isNotEmpty()) {
            draft = if (draft.isEmpty()) unassignedDraft else "$draft\n$unassignedDraft"
            prefs.edit { putString(draftKey(id), draft); remove(draftKey(null)) }
        }
        if (unassignedAttachments.isNotEmpty()) {
            addAttachmentsForDevice(id, unassignedAttachments)
            prefs.edit { remove(attachmentsKey(null)) }
        }
        notice = null
    }

    fun editDraft(text: String) {
        draft = text
        prefs.edit { putString(draftKey(selectedId), text) }
        notice = null
    }

    fun appendRecognition(text: String) {
        if (text.isBlank()) return
        editDraft(if (draft.isBlank()) text else draft + if (draft.endsWith('\n')) text else "\n$text")
    }

    fun clearDraft() {
        editDraft("")
        attachments = emptyList()
        writeAttachments(selectedId, attachments)
    }

    fun removeAttachment(uri: String) {
        attachments = attachments.filterNot { it.uri == uri }
        writeAttachments(selectedId, attachments)
        notice = null
    }

    internal fun addAttachmentsForDevice(deviceId: String?, added: List<LiteAttachment>) {
        val existing = if (selectedId == deviceId) attachments else readAttachments(deviceId)
        val updated = (existing + added).distinctBy { it.uri }
        writeAttachments(deviceId, updated)
        if (selectedId == deviceId) attachments = updated
    }

    fun addPickedFiles(uris: List<Uri>, deviceId: String?) {
        if (uris.isEmpty()) return
        pendingAttachmentLoads++
        loadingAttachments = true
        if (selectedId == deviceId) notice = R.string.lite_adding_files
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    uris.distinct().map { uri -> runCatching { readPickedAttachment(uri) } }
                }
                addAttachmentsForDevice(deviceId, results.mapNotNull { it.getOrNull() })
                if (selectedId == deviceId) {
                    notice = if (results.any { it.isFailure }) R.string.lite_attachment_failed else null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (selectedId == deviceId) notice = R.string.lite_attachment_failed
            } finally {
                pendingAttachmentLoads--
                loadingAttachments = pendingAttachmentLoads > 0
            }
        }
    }

    private fun readPickedAttachment(uri: Uri): LiteAttachment {
        val resolver = getApplication<Application>().contentResolver
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
        return LiteAttachment(uri.toString(), name?.takeIf { it.isNotBlank() }
            ?: getApplication<Application>().getString(android.R.string.untitled),
            resolver.getType(uri), size)
    }

    private fun readAttachments(deviceId: String?): List<LiteAttachment> = try {
        val values = JSONArray(prefs.getString(attachmentsKey(deviceId), "[]"))
        (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            val uri = value.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LiteAttachment(uri, value.optString("name").ifBlank { getApplication<Application>().getString(android.R.string.untitled) },
                if (value.has("mimeType") && !value.isNull("mimeType")) value.getString("mimeType") else null,
                if (value.has("size") && !value.isNull("size")) value.getLong("size") else null)
        }.distinctBy { it.uri }
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeAttachments(deviceId: String?, values: List<LiteAttachment>) {
        val serialized = JSONArray()
        values.forEach { attachment ->
            serialized.put(JSONObject().put("uri", attachment.uri).put("name", attachment.name).apply {
                attachment.mimeType?.let { put("mimeType", it) }
                attachment.size?.let { put("size", it) }
            })
        }
        prefs.edit { putString(attachmentsKey(deviceId), serialized.toString()) }
    }

    fun updateDefaultHome(enabled: Boolean) {
        defaultHome = enabled
        defaults.edit { putBoolean(DEFAULT_HOME_KEY, enabled) }
    }

    fun updateHoldToTalk(enabled: Boolean) {
        holdToTalk = enabled
        prefs.edit { putBoolean("hold_to_talk", enabled) }
    }

    fun showNotice(message: Int) { notice = message }

    fun clearNotice() { notice = null }

    fun fileTransferDeviceId(): String? {
        val target = selectedId
        refreshDevices()
        if (selectedId != target) {
            notice = R.string.lite_device_changed
            return null
        }
        if (selected?.connected != true) {
            notice = R.string.lite_disconnected_hint
            return null
        }
        if (KdeConnect.getInstance().getDevicePlugin<SharePlugin>(target) == null) {
            notice = R.string.lite_share_unavailable
            return null
        }
        notice = null
        return target
    }

    private fun readyPlugin(): MousePadPlugin? {
        val target = selectedId
        refreshDevices()
        if (selectedId != target) {
            notice = R.string.lite_device_changed
            return null
        }
        if (selected?.connected != true) {
            notice = R.string.lite_disconnected_hint
            return null
        }
        return KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(selectedId)
            ?.takeIf { it.isKeyboardEnabled }
            .also { if (it == null) notice = R.string.lite_keyboard_unavailable }
    }

    fun sendText() {
        if (sending || loadingAttachments) return
        if (attachments.isNotEmpty()) {
            sendContents(withEnter = false)
            return
        }
        if (draft.isBlank()) return
        val plugin = readyPlugin() ?: return
        plugin.sendText(draft)
        // KDE's send API queues the packet, and is not an acknowledgement from the focused app.
        // Keep the draft for retry/recovery even if the link fails after the readiness check.
        notice = R.string.lite_text_queued
    }

    fun sendTextAndEnter() {
        if (sending || loadingAttachments) return
        if (attachments.isNotEmpty()) {
            sendContents(withEnter = true)
            return
        }
        val text = draft
        if (text.isBlank()) return
        val plugin = readyPlugin() ?: return
        // Both calls use the same device's ordered queue; no delay or target re-selection.
        plugin.sendText(text)
        notice = when (RemoteShortcutController.send(plugin, RemoteShortcutController.Shortcut.ENTER)) {
            RemoteShortcutController.SendResult.QUEUED -> R.string.lite_text_enter_queued
            RemoteShortcutController.SendResult.UNAVAILABLE -> R.string.lite_disconnected_hint
            RemoteShortcutController.SendResult.KEYBOARD_DISABLED -> R.string.lite_keyboard_unavailable
        }
    }

    private fun sendContents(withEnter: Boolean) {
        val target = selectedId
        val text = draft
        val files = attachments.toList()
        refreshDevices()
        if (selectedId != target) {
            notice = R.string.lite_device_changed
            return
        }
        if (target == null || selected?.connected != true) {
            notice = R.string.lite_disconnected_hint
            return
        }
        val share = KdeConnect.getInstance().getDevicePlugin<SharePlugin>(target)
        if (share == null) {
            notice = R.string.lite_share_unavailable
            return
        }
        val needsKeyboard = text.isNotBlank() || withEnter
        val keyboard = if (needsKeyboard) KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(target)
            ?.takeIf { it.isKeyboardEnabled } else null
        if (needsKeyboard && keyboard == null) {
            notice = R.string.lite_keyboard_unavailable
            return
        }
        sending = true
        notice = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val uris = files.map { Uri.parse(it.uri) }
                    // Validate retained grants without reading whole files before submitting this batch.
                    uris.forEach { uri ->
                        (resolver.openInputStream(uri) ?: throw IOException("Attachment is unavailable")).use { }
                    }
                    // This returns after local preparation/submission, not after delivery to the peer.
                    share.sendFiles(uris)
                }
                val currentDevice = KdeConnect.getInstance().devices[target]
                if (currentDevice == null || !currentDevice.isPaired || !currentDevice.isReachable) {
                    if (selectedId == target) notice = R.string.lite_disconnected_hint
                    return@launch
                }
                if (needsKeyboard && (keyboard?.isKeyboardEnabled != true ||
                        KdeConnect.getInstance().getDevicePlugin<MousePadPlugin>(target) !== keyboard)) {
                    if (selectedId == target) notice = R.string.lite_keyboard_unavailable
                    return@launch
                }
                if (text.isNotBlank()) keyboard?.sendText(text)
                val result = if (withEnter) RemoteShortcutController.send(keyboard, RemoteShortcutController.Shortcut.ENTER) else null
                if (selectedId == target) {
                    notice = when (result) {
                        RemoteShortcutController.SendResult.UNAVAILABLE -> R.string.lite_disconnected_hint
                        RemoteShortcutController.SendResult.KEYBOARD_DISABLED -> R.string.lite_keyboard_unavailable
                        else -> if (withEnter) R.string.lite_contents_enter_queued else R.string.lite_contents_queued
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (selectedId == target) notice = R.string.lite_attachment_failed
            } finally {
                sending = false
            }
        }
    }

    fun sendShortcut(shortcut: RemoteShortcutController.Shortcut) {
        if (sending || loadingAttachments) return
        val plugin = readyPlugin() ?: return
        when (RemoteShortcutController.send(plugin, shortcut)) {
            RemoteShortcutController.SendResult.QUEUED -> notice = R.string.lite_shortcut_queued
            RemoteShortcutController.SendResult.UNAVAILABLE -> notice = R.string.lite_disconnected_hint
            RemoteShortcutController.SendResult.KEYBOARD_DISABLED -> notice = R.string.lite_keyboard_unavailable
        }
    }

    companion object {
        const val DEFAULT_HOME_KEY = "pref_lite_default_home"
        internal fun draftKey(deviceId: String?) = "draft_${deviceId.orEmpty()}"
        internal fun attachmentsKey(deviceId: String?) = "attachments_${deviceId.orEmpty()}"
    }
}
