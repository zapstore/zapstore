package dev.zapstore.app.screens

import dev.zapstore.app.components.Forge
import dev.zapstore.app.components.InstallSource
import dev.zapstore.app.components.installSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallSourceLabelTest {
    @Test
    fun `a known forge reads as its logo and owner and repo`() {
        assertEquals(
            InstallSource(Forge.GitHub, "zapstore/indexer"),
            installSource("https://github.com/zapstore/indexer/releases/download/v1/app.apk"),
        )
        assertEquals(InstallSource(Forge.GitLab, "owner/repo"), installSource("https://www.gitlab.com/owner/repo/-/releases/1/app.apk"))
        assertEquals(InstallSource(Forge.Codeberg, "me/app"), installSource("https://codeberg.org/me/app/releases/download/1/app.apk"))
    }

    @Test
    fun `an owner that is the repo still reads in full`() {
        assertEquals(InstallSource(Forge.GitHub, "organicmaps/organicmaps"), installSource("https://github.com/organicmaps/organicmaps/releases/x.apk"))
    }

    @Test
    fun `F-Droid reads as its logo and the app id`() {
        assertEquals(InstallSource(Forge.FDroid, "app.comaps.fdroid"), installSource("https://f-droid.org/repo/app.comaps.fdroid_25080802.apk"))
        assertEquals(InstallSource(Forge.FDroid, "org.example.app"), installSource("https://f-droid.org/en/packages/org.example.app/"))
        assertNull(installSource("https://f-droid.org/").forge)
    }

    @Test
    fun `any other address stays whole, or is cut to its host`() {
        assertEquals(InstallSource(null, "cdn.example.com/files/app.apk"), installSource("https://cdn.example.com/files/app.apk"))
        assertEquals(InstallSource(null, "cdn.example.com"), installSource("https://CDN.example.com/files/app.apk", whole = false))
        assertNull(installSource("https://github.com/zapstore").forge)
        assertEquals("not a url", installSource(" not a url ").label)
    }
}
