/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.lite

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.ui.lite.RemoteShortcutController.SendResult
import org.kde.kdeconnect.ui.lite.RemoteShortcutController.Shortcut

class RemoteShortcutControllerTest {
    @Test
    fun screenshotUsesWindowsShiftSInOneKeyboardPacket() {
        val packet = RemoteShortcutController.packetFor(Shortcut.SCREENSHOT)

        assertEquals(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST, packet.type)
        assertEquals("s", packet.getString("key"))
        assertTrue(packet.getBoolean("super"))
        assertTrue(packet.getBoolean("shift"))
        assertFalse(packet.getBoolean("ctrl"))
        assertFalse(packet.getBoolean("alt"))
        assertNull(packet.getIntOrNull("specialKey"))
        assertFalse(packet.hasPayload())
    }

    @Test
    fun copyPasteAndUndoUseControlWithLowercaseLetters() {
        mapOf(Shortcut.COPY to "c", Shortcut.PASTE to "v", Shortcut.UNDO to "z").forEach { (shortcut, key) ->
            val packet = RemoteShortcutController.packetFor(shortcut)

            assertEquals(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST, packet.type)
            assertEquals(key, packet.getString("key"))
            assertTrue(packet.getBoolean("ctrl"))
            assertFalse(packet.getBoolean("super"))
            assertFalse(packet.getBoolean("shift"))
            assertFalse(packet.getBoolean("alt"))
            assertNull(packet.getIntOrNull("specialKey"))
        }
    }

    @Test
    fun enterAndEscapeUseProtocolCodesWithoutTextOrModifiers() {
        mapOf(Shortcut.ENTER to 12, Shortcut.ESCAPE to 14).forEach { (shortcut, code) ->
            val packet = RemoteShortcutController.packetFor(shortcut)

            assertEquals(MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST, packet.type)
            assertEquals(code, packet.getInt("specialKey"))
            assertNull(packet.getStringOrNull("key"))
            listOf("ctrl", "super", "shift", "alt").forEach { modifier ->
                assertNull(packet.getStringOrNull(modifier))
            }
        }
    }

    @Test
    fun modifiersNeverLeakFromThePreviousShortcut() {
        RemoteShortcutController.packetFor(Shortcut.SCREENSHOT)
        RemoteShortcutController.packetFor(Shortcut.COPY)

        val enter = RemoteShortcutController.packetFor(Shortcut.ENTER)
        assertFalse(enter.getBoolean("super"))
        assertFalse(enter.getBoolean("shift"))
        assertFalse(enter.getBoolean("ctrl"))
        assertEquals(12, enter.getInt("specialKey"))
    }

    @Test
    fun unavailableOrDisabledKeyboardDoesNotSend() {
        assertEquals(SendResult.UNAVAILABLE, RemoteShortcutController.send(null, Shortcut.PASTE))

        val plugin = mockk<MousePadPlugin> {
            every { isKeyboardEnabled } returns false
        }
        assertEquals(SendResult.KEYBOARD_DISABLED, RemoteShortcutController.send(plugin, Shortcut.PASTE))
        verify(exactly = 0) { plugin.sendPacket(any()) }
    }

    @Test
    fun enabledKeyboardQueuesExactlyOneShortcutPacket() {
        val plugin = mockk<MousePadPlugin> {
            every { isKeyboardEnabled } returns true
            every { sendPacket(any()) } returns Unit
        }

        assertEquals(SendResult.QUEUED, RemoteShortcutController.send(plugin, Shortcut.PASTE))
        verify(exactly = 1) {
            plugin.sendPacket(match {
                it.type == MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST &&
                    it.getString("key") == "v" && it.getBoolean("ctrl")
            })
        }
    }
}
