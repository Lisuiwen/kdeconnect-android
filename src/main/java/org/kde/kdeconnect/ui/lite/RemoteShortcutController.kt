/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.lite

import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin

/** Sends shortcuts through the existing keyboard protocol without changing clipboard contents. */
object RemoteShortcutController {
    enum class Shortcut {
        SCREENSHOT,
        COPY,
        PASTE,
        ENTER,
        ESCAPE,
        UNDO,
    }

    enum class SendResult {
        QUEUED,
        UNAVAILABLE,
        KEYBOARD_DISABLED,
    }

    /** QUEUED reports local acceptance, not confirmation from the application on the desktop. */
    fun send(plugin: MousePadPlugin?, shortcut: Shortcut): SendResult {
        if (plugin == null) return SendResult.UNAVAILABLE
        if (!plugin.isKeyboardEnabled) return SendResult.KEYBOARD_DISABLED
        plugin.sendPacket(packetFor(shortcut))
        return SendResult.QUEUED
    }

    internal fun packetFor(shortcut: Shortcut): NetworkPacket {
        return NetworkPacket(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST).apply {
            when (shortcut) {
                Shortcut.SCREENSHOT -> {
                    this["key"] = "s"
                    this["super"] = true
                    this["shift"] = true
                }
                Shortcut.COPY -> {
                    this["key"] = "c"
                    this["ctrl"] = true
                }
                Shortcut.PASTE -> {
                    this["key"] = "v"
                    this["ctrl"] = true
                }
                Shortcut.UNDO -> {
                    this["key"] = "z"
                    this["ctrl"] = true
                }
                // Values are defined by the KDE Connect mousepad protocol, not Android KeyEvent.
                Shortcut.ENTER -> this["specialKey"] = 12
                Shortcut.ESCAPE -> this["specialKey"] = 14
            }
        }
    }
}
