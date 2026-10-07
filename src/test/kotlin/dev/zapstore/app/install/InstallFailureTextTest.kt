package dev.zapstore.app.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallFailureTextTest {
    @Test
    fun namesSamsungAutoBlockerAndKeepsTheSystemMessage() {
        val text = installFailureText(PackageInstaller.STATUS_FAILURE_BLOCKED, "samsung", "INSTALL_FAILED_BLOCKED")
        assertTrue(text.contains("Auto Blocker"))
        assertTrue(text.contains("INSTALL_FAILED_BLOCKED"))
    }

    @Test
    fun namesHyperOsForXiaomi() {
        val text = installFailureText(PackageInstaller.STATUS_FAILURE_BLOCKED, "Xiaomi", null)
        assertTrue(text.contains("HyperOS"))
    }

    @Test
    fun keepsAGenericFailureMessage() {
        val text = installFailureText(PackageInstaller.STATUS_FAILURE, "Google", "Advanced Protection blocked this app")
        assertTrue(text.contains("Advanced Protection"))
    }
}
