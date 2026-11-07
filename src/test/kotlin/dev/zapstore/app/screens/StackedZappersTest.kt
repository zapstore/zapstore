package dev.zapstore.app.screens

import dev.zapstore.iolite.ProfileRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class StackedZappersTest {
    private val anon = "1".repeat(64)
    private val blank = "2".repeat(64)
    private val alice = "a".repeat(64)
    private val photo = "b".repeat(64)
    private val bob = "c".repeat(64)
    private val extra = "d".repeat(64)
    private val third = "e".repeat(64)

    @Test
    fun aNameWithoutAPictureStaysOutOfTheStack() {
        val zappers = listOf(
            Zapper(anon, 9_000),
            Zapper(alice, 8_000),
            Zapper(blank, 7_000),
            Zapper(photo, 1_000),
            Zapper(bob, 500),
            Zapper(extra, 100),
            Zapper(third, 50),
        )
        val profiles = mapOf(
            blank to profile(blank),
            alice to profile(alice, name = "Alice"),
            photo to profile(photo, picture = "https://cdn.example/p.webp"),
            bob to profile(bob, displayName = "Bob", picture = "https://cdn.example/b.webp"),
            extra to profile(extra, name = "Eve"),
            third to profile(third, picture = "https://cdn.example/t.webp"),
        )

        assertEquals(listOf(photo, bob, third), stackedZappers(zappers, profiles).map { it.pubkey })
    }

    @Test
    fun everyPicturedZapperIsACandidate() {
        val pictured = (1..7).map { n ->
            val pubkey = n.toString().repeat(64)
            Zapper(pubkey, (8 - n) * 100L) to profile(pubkey, picture = "https://cdn.example/$n.webp")
        }
        val zappers = listOf(Zapper(alice, 9_000)) + pictured.map { it.first }
        val profiles = mapOf(alice to profile(alice, name = "Alice")) + pictured.associate { it.first.pubkey to it.second }

        assertEquals(pictured.map { it.first.pubkey }, stackedZappers(zappers, profiles).map { it.pubkey })
    }

    @Test
    fun theLineFitsAsManyFacesAsTheWidthHolds() {
        assertEquals(5, facesThatFit(9, 100, 40, 260))
        assertEquals(6, facesThatFit(9, 100, 40, 290))
        assertEquals(3, facesThatFit(3, 100, 40, 1_000))
        assertEquals(0, facesThatFit(4, 100, 40, 50))
    }

    @Test
    fun facesOverlapMoreWhenTheLineIsShort() {
        assertEquals(FacePack(100, 40, 260), packFaces(5, 100, 40, 400))
        assertEquals(FacePack(100, 25, 200), packFaces(5, 100, 40, 200))
        assertEquals(FacePack(80, 0, 80), packFaces(5, 100, 40, 80))
    }

    private fun profile(
        pubkey: String,
        name: String? = null,
        displayName: String? = null,
        picture: String? = null,
    ) = ProfileRecord(pubkey, 0, "f".repeat(64), name, displayName, null, picture, null, null, null)
}
