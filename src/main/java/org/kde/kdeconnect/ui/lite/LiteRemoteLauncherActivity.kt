/*
 * SPDX-FileCopyrightText: 2026 KDE Connect Lite Remote contributors
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.ui.lite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.ui.MainActivity

/** A separate, transient task lets every launcher click apply the current home preference. */
class LiteRemoteLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val liteHome = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(LiteRemoteViewModel.DEFAULT_HOME_KEY, true)
        val target = if (liteHome) LiteRemoteActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }
}
