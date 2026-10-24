/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Lite Remote contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect_tp.R

data class LiteDevice(val id: String, val name: String, val connected: Boolean, val inputEnabled: Boolean)

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
    var defaultHome by mutableStateOf(defaults.getBoolean(DEFAULT_HOME_KEY, true))
        private set
    var holdToTalk by mutableStateOf(prefs.getBoolean("hold_to_talk", true))
        private set
    var notice by mutableStateOf<Int?>(null)
        private set
    var refreshing by mutableStateOf(false)

    val selected: LiteDevice? get() = devices.firstOrNull { it.id == selectedId }
    val canSend: Boolean get() = selected?.let { it.connected && it.inputEnabled } == true

    fun refreshDevices() {
        devices = KdeConnect.getInstance().devices.values.filter {
            it.isPaired && it.deviceType in setOf(DeviceType.DESKTOP, DeviceType.LAPTOP)
        }
            .map { device ->
                LiteDevice(device.deviceId, device.name, device.isReachable,
                    device.getPlugin<MousePadPlugin>()?.isKeyboardEnabled == true)
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
        selectedId = id
        prefs.edit { putString("selected_device", id) }
        draft = prefs.getString(draftKey(id), "").orEmpty()
        if (unassignedDraft.isNotEmpty()) {
            draft = if (draft.isEmpty()) unassignedDraft else "$draft\n$unassignedDraft"
            prefs.edit { putString(draftKey(id), draft); remove(draftKey(null)) }
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
        if (draft.isBlank()) return
        val plugin = readyPlugin() ?: return
        plugin.sendText(draft)
        // KDE's send API queues the packet, and is not an acknowledgement from the focused app.
        // Keep the draft for retry/recovery even if the link fails after the readiness check.
        notice = R.string.lite_text_queued
    }

    fun sendShortcut(shortcut: RemoteShortcutController.Shortcut) {
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
    }
}
