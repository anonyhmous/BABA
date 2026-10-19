package com.driveflow.driver.core.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.driveflow.driver.service.DriveFlowAccessibilityService

/** Asks Android whether the DriveFlow accessibility service is switched on in system settings. */
object AccessibilityStatus {
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DriveFlowAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }
}
