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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceType
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.mousepad.MousePadPlugin
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
        mockkObject(KdeConnect.Companion)
        every { KdeConnect.getInstance() } returns kde
    }

    @After
    fun cleanup() { unmockkObject(KdeConnect.Companion) }

    private fun device(id: String, name: String, connected: Boolean, plugin: MousePadPlugin? = null): Device = mockk {
        every { deviceId } returns id
        every { this@mockk.name } returns name
        every { isPaired } returns true
        every { deviceType } returns DeviceType.DESKTOP
        every { isReachable } returns connected
        every { getPlugin(MousePadPlugin::class.java) } returns plugin
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
}
