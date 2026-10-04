package com.ahdownload.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignAuditTest {
    @Test
    fun allPrimaryScreensHaveContracts() {
        val expected = setOf("home", "downloads", "studio", "settings", "accounts", "diagnostics")
        assertEquals(expected, DesignAudit.contractNames())
        expected.forEach { assertNotNull(DesignAudit.expectedElements(it)) }
    }

    @Test
    fun contractsContainRequiredSurfaceElements() {
        assertTrue(DesignAudit.expectedElements("home")!!.contains("analysis_result"))
        assertTrue(DesignAudit.expectedElements("downloads")!!.contains("download_cards"))
        assertTrue(DesignAudit.expectedElements("studio")!!.contains("processing_actions"))
        assertTrue(DesignAudit.expectedElements("accounts")!!.contains("login_action"))
        assertTrue(DesignAudit.expectedElements("diagnostics")!!.contains("runtime_log"))
    }
}
