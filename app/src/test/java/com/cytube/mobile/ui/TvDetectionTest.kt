package com.cytube.mobile.ui

import android.app.TestUiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDetectionTest {

    private class FakeContext(
        private val systemService: Any?,
        private val pm: PackageManager? = null
    ) : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? {
            return if (name == UI_MODE_SERVICE) systemService else null
        }

        override fun getPackageManager(): PackageManager {
            return pm ?: throw UnsupportedOperationException()
        }
    }

    @Test
    fun `defaultSyncAccuracy returns 2_0 when UiModeManager is null`() {
        val context = FakeContext(null)
        assertFalse(isTvDevice(context))
        assertEquals(2.0, defaultSyncAccuracy(context), 0.0)
    }

    @Test
    fun `defaultSyncAccuracy returns 2_0 for normal mode type`() {
        val uiModeManager = TestUiModeManager(Configuration.UI_MODE_TYPE_NORMAL)
        val context = FakeContext(uiModeManager)
        assertFalse(isTvDevice(context))
        assertEquals(2.0, defaultSyncAccuracy(context), 0.0)
    }

    @Test
    fun `defaultSyncAccuracy returns 5_0 for television mode type`() {
        val uiModeManager = TestUiModeManager(Configuration.UI_MODE_TYPE_TELEVISION)
        val context = FakeContext(uiModeManager)
        assertTrue(isTvDevice(context))
        assertEquals(5.0, defaultSyncAccuracy(context), 0.0)
    }
}
