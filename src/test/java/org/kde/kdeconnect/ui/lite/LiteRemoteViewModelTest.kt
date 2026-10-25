/*
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect_tp.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class LiteRemoteViewModelTest {
    private lateinit var app: Application
    private val registry = ConcurrentHashMap<String, Device>()

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("lite_remote", Context.MODE_PRIVATE).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        val kde = mockk<KdeConnect>()
        every { kde.devices } returns registry
        every { kde.getDevicePlugin(any<String>(), MousePadPlugin::class.java) } answers {
            firstArg<String?>()?.let(registry::get)?.getPlugin(MousePadPlugin::class.java)
        }
        every { kde.getDevicePlugin(any<String>(), SharePlugin::class.java) } answers {
            firstArg<String?>()?.let(registry::get)?.getPlugin(SharePlugin::class.java)
        }
        mockkObject(KdeConnect.Companion)
        every { KdeConnect.getInstance() } returns kde
    }

    @After
    fun cleanup() { unmockkObject(KdeConnect.Companion) }

    private fun device(
        id: String,
        name: String,
        connected: Boolean,
        plugin: MousePadPlugin? = null,
        sharePlugin: SharePlugin? = null,
    ): Device = mockk {
        every { deviceId } returns id
        every { this@mockk.name } returns name
        every { isPaired } returns true
        every { deviceType } returns DeviceType.DESKTOP
        every { isReachable } returns connected
        every { getPlugin(MousePadPlugin::class.java) } returns plugin
        every { getPlugin(SharePlugin::class.java) } returns sharePlugin
    }

    @Test
    fun draftSurvivesRecreationAndIsIsolatedPerDevice() {
        val first = LiteRemoteViewModel(app)
        first.selectDevice("a")
        first.editDraft("中文 Android SDK\n🙂")
        first.selectDevice("b")
        assertEquals("", first.draft)
        first.editDraft("second")
        val restored = LiteRemoteViewModel(app)
        assertEquals("b", restored.selectedId)
        assertEquals("second", restored.draft)
        restored.selectDevice("a")
        assertEquals("中文 Android SDK\n🙂", restored.draft)
    }

    @Test
    fun anonymousDraftMigratesToFirstPairedComputer() {
        val model = LiteRemoteViewModel(app)
        model.editDraft("before pairing")
        registry["brook-id"] = device("brook-id", "Brook", false)
        model.refreshDevices()
        assertEquals("brook-id", model.selectedId)
        assertEquals("before pairing", model.draft)
    }

    @Test
    fun selectionPrefersBrookButDoesNotFollowAnotherDeviceWhenUnpaired() {
        registry["a"] = device("a", "Other", true)
        registry["b"] = device("b", "Brook", false)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        assertEquals("b", model.selectedId)
        model.editDraft("keep")
        registry.remove("b")
        model.refreshDevices()
        assertEquals("b", model.selectedId)
        assertEquals("keep", model.draft)
        assertFalse(model.canSend)
    }

    @Test
    fun emptyRecognitionKeepsDraftAndNewRecognitionAppends() {
        val model = LiteRemoteViewModel(app)
        model.editDraft("existing")
        model.appendRecognition(" ")
        assertEquals("existing", model.draft)
        model.appendRecognition("新内容")
        assertEquals("existing\n新内容", model.draft)
    }

    @Test
    fun disconnectedSendPreservesDraftAndSendsNothing() {
        val plugin = mockk<MousePadPlugin>(relaxed = true)
        every { plugin.isKeyboardEnabled } returns true
        registry["a"] = device("a", "Brook", false, plugin)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        model.editDraft("draft")
        model.sendText()
        assertEquals("draft", model.draft)
        verify(exactly = 0) { plugin.sendText(any()) }
    }

    @Test
    fun sendQueuesOnlyTextWithoutEnterAndKeepsRecoveryDraft() {
        val plugin = mockk<MousePadPlugin>(relaxed = true)
        every { plugin.isKeyboardEnabled } returns true
        registry["a"] = device("a", "Brook", true, plugin)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        model.editDraft("中文🙂\nnext line")
        model.sendText()
        assertEquals("中文🙂\nnext line", model.draft)
        verify(exactly = 1) { plugin.sendText("中文🙂\nnext line") }
        verify(exactly = 0) { plugin.sendSelect() }
        verify(exactly = 0) { plugin.sendPacket(any()) }
    }

    @Test
    fun combinedSendQueuesOriginalTextBeforeExactlyOneEnterAndKeepsDraft() {
        val plugin = mockk<MousePadPlugin>(relaxed = true)
        every { plugin.isKeyboardEnabled } returns true
        registry["a"] = device("a", "Brook", true, plugin)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        val text = "  中文🙂\nnext line  "
        model.editDraft(text)
        model.sendTextAndEnter()

        assertEquals(text, model.draft)
        verify(exactly = 1) { plugin.sendText(text) }
        verify(exactly = 1) { plugin.sendPacket(any()) }
        verifyOrder {
            plugin.sendText(text)
            plugin.sendPacket(match {
                it.type == MousePadPlugin.PACKET_TYPE_MOUSEPAD_REQUEST &&
                    it.getInt("specialKey") == 12 && !it.has("key")
            })
        }
    }

    @Test
    fun combinedSendWithBlankDraftDoesNotSendTextOrEnter() {
        val plugin = mockk<MousePadPlugin>(relaxed = true)
        every { plugin.isKeyboardEnabled } returns true
        registry["a"] = device("a", "Brook", true, plugin)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        model.editDraft(" \n ")
        model.sendTextAndEnter()

        assertEquals(" \n ", model.draft)
        verify(exactly = 0) { plugin.sendText(any()) }
        verify(exactly = 0) { plugin.sendPacket(any()) }
    }

    @Test
    fun combinedSendWithoutConnectionOrKeyboardSendsNothing() {
        listOf(false to true, true to false).forEach { (connected, keyboardEnabled) ->
            val plugin = mockk<MousePadPlugin>(relaxed = true)
            every { plugin.isKeyboardEnabled } returns keyboardEnabled
            registry["a"] = device("a", "Brook", connected, plugin)
            val model = LiteRemoteViewModel(app)
            model.refreshDevices()
            model.editDraft("keep this draft")
            model.sendTextAndEnter()

            assertEquals("keep this draft", model.draft)
            verify(exactly = 0) { plugin.sendText(any()) }
            verify(exactly = 0) { plugin.sendPacket(any()) }
        }
    }

    @Test
    fun fileSharingUsesSelectedComputerWithoutDependingOnKeyboardInput() {
        val keyboard = mockk<MousePadPlugin>(relaxed = true)
        every { keyboard.isKeyboardEnabled } returns false
        val share = mockk<SharePlugin>()
        registry["a"] = device("a", "Brook", true, keyboard, share)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()

        assertFalse(model.canSend)
        assertTrue(model.canSendFiles)
        assertEquals("a", model.fileTransferDeviceId())
        verify(exactly = 0) { keyboard.sendPacket(any()) }
    }

    @Test
    fun fileSharingRechecksConnectionAndSharePluginAtClickTime() {
        listOf(false to true, true to false).forEach { (connected, sharingEnabled) ->
            val keyboard = mockk<MousePadPlugin>(relaxed = true)
            every { keyboard.isKeyboardEnabled } returns true
            val share = mockk<SharePlugin>()
            registry["a"] = device("a", "Brook", true, keyboard, share)
            val model = LiteRemoteViewModel(app)
            model.refreshDevices()
            assertTrue(model.canSendFiles)

            registry["a"] = device("a", "Brook", connected, keyboard, if (sharingEnabled) share else null)
            assertNull(model.fileTransferDeviceId())
            assertFalse(model.canSendFiles)
            assertEquals(if (connected) R.string.lite_share_unavailable else R.string.lite_disconnected_hint, model.notice)
            assertEquals(connected, model.canSend)
        }
    }

    @Test
    fun fileSharingDoesNotFallBackToAnotherComputerWhenSelectedDeviceDisappears() {
        val share = mockk<SharePlugin>()
        registry["a"] = device("a", "Brook", true, sharePlugin = share)
        registry["b"] = device("b", "Other", true, sharePlugin = share)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        assertEquals("a", model.selectedId)
        registry.remove("a")

        assertNull(model.fileTransferDeviceId())
        assertEquals("a", model.selectedId)
        assertFalse(model.canSendFiles)
        assertEquals(R.string.lite_disconnected_hint, model.notice)
    }

    @Test
    fun fileSharingAbortsWhenInitialRefreshChangesAStaleSavedTarget() {
        app.getSharedPreferences("lite_remote", Context.MODE_PRIVATE).edit()
            .putString("selected_device", "gone").commit()
        registry["b"] = device("b", "Brook", true, sharePlugin = mockk<SharePlugin>())
        val model = LiteRemoteViewModel(app)

        assertNull(model.fileTransferDeviceId())
        assertEquals("b", model.selectedId)
        assertEquals(R.string.lite_device_changed, model.notice)
    }

    @Test
    fun attachmentOnlyDraftCanSendWithoutKeyboardButEnterIsBlocked() {
        val keyboard = mockk<MousePadPlugin>(relaxed = true)
        every { keyboard.isKeyboardEnabled } returns false
        val share = mockk<SharePlugin>(relaxed = true)
        registry["a"] = device("a", "Brook", true, keyboard, share)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        model.addAttachmentsForDevice("a", listOf(LiteAttachment("content://files/photo", "photo.png", "image/png", 123)))

        assertTrue(model.hasDraft)
        assertTrue(model.canSendDraft)
        assertFalse(model.canSendDraftAndEnter)
        model.sendTextAndEnter()
        assertFalse(model.sending)
        assertEquals(R.string.lite_keyboard_unavailable, model.notice)
        verify(exactly = 0) { share.sendFiles(any(), any()) }
        verify(exactly = 0) { keyboard.sendPacket(any()) }
    }

    @Test
    fun mixedDraftRequiresBothPluginsAndWhitespaceAloneCannotSend() {
        val keyboard = mockk<MousePadPlugin>(relaxed = true)
        every { keyboard.isKeyboardEnabled } returns false
        val share = mockk<SharePlugin>()
        registry["a"] = device("a", "Brook", true, keyboard, share)
        val model = LiteRemoteViewModel(app)
        model.refreshDevices()
        model.editDraft(" \n ")
        assertTrue(model.hasDraft)
        assertFalse(model.canSendDraft)

        model.addAttachmentsForDevice("a", listOf(LiteAttachment("content://files/a", "a.txt")))
        assertTrue(model.canSendDraft)
        model.editDraft("send this text too")
        assertFalse(model.canSendDraft)
        every { keyboard.isKeyboardEnabled } returns true
        model.refreshDevices()
        assertTrue(model.canSendDraft)
        assertTrue(model.canSendDraftAndEnter)
    }

    @Test
    fun attachmentsRestorePerDeviceAndLatePickerResultStaysWithOriginalTarget() {
        val photo = LiteAttachment("content://files/photo", "photo.png", "image/png", 123)
        val document = LiteAttachment("content://files/document", "document.pdf", "application/pdf", null)
        val model = LiteRemoteViewModel(app)
        model.selectDevice("a")
        model.editDraft("a's text")
        model.addAttachmentsForDevice("a", listOf(photo))
        model.selectDevice("b")
        model.addAttachmentsForDevice("b", listOf(document))
        val lateFile = LiteAttachment("content://files/late", "late.txt")
        model.addAttachmentsForDevice("a", listOf(lateFile))
        assertEquals("b", model.selectedId)
        assertEquals(listOf(document), model.attachments)

        val restored = LiteRemoteViewModel(app)
        assertEquals("b", restored.selectedId)
        assertEquals(listOf(document), restored.attachments)
        restored.selectDevice("a")
        assertEquals("a's text", restored.draft)
        assertEquals(listOf(photo, lateFile), restored.attachments)
    }

    @Test
    fun anonymousAttachmentsMigrateWithTextToFirstPairedComputer() {
        val attachment = LiteAttachment("content://files/unassigned", "unassigned.txt")
        val model = LiteRemoteViewModel(app)
        model.editDraft("before pairing")
        model.addAttachmentsForDevice(null, listOf(attachment))
        registry["brook-id"] = device("brook-id", "Brook", false)
        model.refreshDevices()

        assertEquals("brook-id", model.selectedId)
        assertEquals("before pairing", model.draft)
        assertEquals(listOf(attachment), model.attachments)
        assertEquals(listOf(attachment), LiteRemoteViewModel(app).attachments)
        assertFalse(app.getSharedPreferences("lite_remote", Context.MODE_PRIVATE)
            .contains(LiteRemoteViewModel.attachmentsKey(null)))
    }

    @Test
    fun removingAndDeduplicatingAttachmentsPreservesTextAndClearRemovesEverything() {
        val first = LiteAttachment("content://files/one", "one.txt")
        val second = LiteAttachment("content://files/two", "two.txt")
        val model = LiteRemoteViewModel(app)
        model.selectDevice("a")
        model.editDraft("keep text")
        model.addAttachmentsForDevice("a", listOf(first, first, second))
        model.addAttachmentsForDevice("a", listOf(first.copy(name = "same URI")))
        assertEquals(listOf(first, second), model.attachments)

        model.removeAttachment(first.uri)
        assertEquals("keep text", model.draft)
        assertEquals(listOf(second), model.attachments)
        assertEquals(listOf(second), LiteRemoteViewModel(app).attachments)
        model.clearDraft()
        assertFalse(model.hasDraft)
        val restored = LiteRemoteViewModel(app)
        assertEquals("", restored.draft)
        assertTrue(restored.attachments.isEmpty())
    }
}
