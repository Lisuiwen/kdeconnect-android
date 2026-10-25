/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Lite Remote contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect_tp.R

class LiteRemoteActivity : AppCompatActivity() {
    private lateinit var model: LiteRemoteViewModel
    private lateinit var voice: VoiceInputController
    private var attachmentTarget: String? = null
    private var awaitingAttachments = false
    private val handler = Handler(Looper.getMainLooper())
    private val observerKey = "lite_remote_${System.identityHashCode(this)}"
    private val refresh = object : Runnable {
        override fun run() {
            model.refreshDevices()
            handler.postDelayed(this, 1000)
        }
    }
    private val stopRefreshing = Runnable { if (::model.isInitialized) model.refreshing = false }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Do not start recording after a permission dialog: a PTT finger may already be released.
        model.showNotice(if (granted) R.string.lite_mic_granted else R.string.lite_mic_permission)
    }
    private val connectionPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        BackgroundService.ForceRefreshConnections(this)
    }
    private val chooseAttachments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = attachmentTarget
        val expected = awaitingAttachments
        attachmentTarget = null
        awaitingAttachments = false
        if (uris.isNotEmpty()) {
            if (expected) model.addPickedFiles(uris, target)
            else model.showNotice(R.string.lite_device_changed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        attachmentTarget = savedInstanceState?.getString(ATTACHMENT_TARGET)
        awaitingAttachments = savedInstanceState?.getBoolean(AWAITING_ATTACHMENTS) ?: false
        model = ViewModelProvider(this)[LiteRemoteViewModel::class.java]
        model.refreshDevices()
        intent.getStringExtra("deviceId")?.takeIf { id -> model.devices.any { it.id == id } }?.let(model::selectDevice)
        voice = VoiceInputController(this, model::appendRecognition)
        setContent {
            val voiceState by voice.state.collectAsState()
            KdeTheme(this) {
                LiteRemoteScreen(model, voiceState,
                    onStartVoice = ::startVoice,
                    onStopVoice = { voice.stop() },
                    onCancelVoice = { voice.cancel() },
                    onSelectDevice = { voice.cancel(); model.selectDevice(it) },
                    onRefresh = ::refreshConnections,
                    onFullApp = ::openFullApp,
                    onSendFiles = ::openSendFiles,
                    onAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) },
                    onVoiceMode = { voice.cancel(); model.updateHoldToTalk(it) })
            }
        }
        if (savedInstanceState == null) {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.ACCESS_LOCAL_NETWORK
            }
            if (permissions.isNotEmpty()) connectionPermissions.launch(permissions.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::model.isInitialized) return
        BackgroundService.Start(this)
        KdeConnect.getInstance().addDeviceListChangedCallback(observerKey) {
            handler.post { if (!isFinishing) model.refreshDevices() }
        }
        handler.post(refresh)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::model.isInitialized) return
        intent.getStringExtra("deviceId")?.takeIf { id -> model.devices.any { it.id == id } }?.let {
            voice.cancel()
            model.selectDevice(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(ATTACHMENT_TARGET, attachmentTarget)
        outState.putBoolean(AWAITING_ATTACHMENTS, awaitingAttachments)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (::voice.isInitialized) voice.cancel()
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(stopRefreshing)
        if (::model.isInitialized) model.refreshing = false
        KdeConnect.getInstance().removeDeviceListChangedCallback(observerKey)
        super.onStop()
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startVoice() {
        model.clearNotice()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start(true)
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun refreshConnections() {
        model.refreshing = true
        BackgroundService.ForceRefreshConnections(this)
        handler.removeCallbacks(stopRefreshing)
        handler.postDelayed(stopRefreshing, 5000)
    }

    private fun openFullApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun openSendFiles() {
        if (awaitingAttachments || model.loadingAttachments || model.sending) return
        val target = model.selected?.id ?: run {
            model.showNotice(R.string.lite_pair_hint)
            return
        }
        voice.cancel()
        attachmentTarget = target
        awaitingAttachments = true
        try {
            chooseAttachments.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            attachmentTarget = null
            awaitingAttachments = false
            model.showNotice(R.string.lite_share_unavailable)
        }
    }

    private companion object {
        const val ATTACHMENT_TARGET = "lite_attachment_target"
        const val AWAITING_ATTACHMENTS = "lite_awaiting_attachments"
    }
}
