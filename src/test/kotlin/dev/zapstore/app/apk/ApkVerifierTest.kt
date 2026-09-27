package dev.zapstore.app.apk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifierTest {
    @Test
    fun acceptsMatchingHashPackageAndCertificate() {
        val apk = ApkIdentity("com.example.app", "aa", setOf("cert"), listOf("cert"))
        val listed = ListedApk("com.example.app", "aa", "cert")
        assertTrue(ApkVerifier.isInstallable(apk, listed))
    }

    @Test
    fun rejectsHashPackageOrCertificateMismatch() {
        val apk = ApkIdentity("com.example.app", "aa", setOf("cert"), listOf("cert"))
        assertFalse(ApkVerifier.isInstallable(apk, ListedApk("com.example.app", "bb", "cert")))
        assertFalse(ApkVerifier.isInstallable(apk, ListedApk("com.other", "aa", "cert")))
        assertFalse(ApkVerifier.isInstallable(apk, ListedApk("com.example.app", "aa", "other")))
    }

    @Test
    fun updateAcceptsInstalledCertificateOrLineageSuccessor() {
        val successor = ApkIdentity("com.example.app", "aa", setOf("new"), listOf("old", "new"))
        val listed = ListedApk("com.example.app", "aa", "new")
        assertTrue(ApkVerifier.isInstallable(successor, listed, installedCertificates = setOf("old"), isUpdate = true))
        assertTrue(ApkVerifier.isInstallable(successor, listed, installedCertificates = setOf("new"), isUpdate = true))
        assertFalse(ApkVerifier.isInstallable(successor, listed, installedCertificates = setOf("unrelated"), isUpdate = true))
    }

    @Test
    fun c1ReputationIsNotASubstitute() {
        val apk = ApkIdentity("com.example.app", "aa", setOf("cert"), listOf("cert"))
        val listed = ListedApk("com.example.app", "wrong", "cert")
        assertFalse(ApkVerifier.isInstallable(apk, listed, installedCertificates = setOf("cert"), isUpdate = true))
    }
}
