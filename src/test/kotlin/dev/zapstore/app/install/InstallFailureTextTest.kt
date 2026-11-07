package dev.zapstore.app.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
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

    @Test
    fun aKeyMismatchIsASignatureConflictAndNothingElseIs() {
        val incompatible = "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.example signatures do not match newer version; ignoring!"
        val conflict = PackageInstaller.STATUS_FAILURE_CONFLICT
        assertTrue(isSignatureConflict(conflict, installFailureText(conflict, "Google", incompatible)))
        assertTrue(isSignatureConflict(conflict, "Package com.example signatures do not match previously installed version"))
        assertFalse(isSignatureConflict(conflict, "INSTALL_FAILED_DUPLICATE_PERMISSION: Package com.example attempting to redeclare permission"))
        assertFalse(isSignatureConflict(PackageInstaller.STATUS_FAILURE_INVALID, incompatible))
        assertFalse(isSignatureConflict(conflict, null))
    }
}
