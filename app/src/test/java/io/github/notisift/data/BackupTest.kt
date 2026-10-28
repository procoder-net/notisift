package io.github.notisift.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private val secret = "pplx-SECRET-should-never-leave-the-phone"
    private val settings = AppSettings(apiKey = secret, provider = "perplexity", chatRules = "mute\\tcom.whatsapp\\tHikers", myNames = "Sam Lee", groupByDay = true, tourDone = true)
    private val policies = listOf(
        Policy("com.amazon.mShop.android.shopping", "Amazon", mode = "auto", instructions = "deliveries matter"),
        Policy("com.spotify.music", "Spotify", mode = ""), // only a suggestion: not exported
    )
    private val corrections = listOf(CachedDecision("com.temu|sale", "block", "promo", 1.0, "CORRECTION", "You chose junk", 1L, pinned = true))

    private fun exported() = Backup.encode(Backup.build(settings, policies, corrections, now = 42L))

    @Test fun `export never contains the API key`() {
        assertFalse(exported().contains(secret))
        assertFalse(exported().contains("apiKey"))
    }

    @Test fun `round trip keeps choices, corrections and settings`() {
        val back = Backup.decode(exported())
        assertEquals(listOf("com.amazon.mShop.android.shopping"), back.apps.map { it.packageName })
        assertEquals("deliveries matter", back.apps.single().instructions)
        assertEquals("com.temu|sale", back.corrections.single().templateKey)
        assertTrue(back.settings.groupByDay)
        assertEquals("Sam Lee", back.settings.myNames)
    }

    @Test fun `import keeps this phone's key and own state`() {
        val here = AppSettings(apiKey = "sk-or-new-phone-key", tourDone = false, observe = true)
        val merged = Backup.applyTo(here, Backup.decode(exported()).settings)
        assertEquals("sk-or-new-phone-key", merged.apiKey)
        assertFalse(merged.tourDone)
        assertTrue(merged.observe)
        assertEquals("Sam Lee", merged.myNames)
    }

    @Test fun `rejects files that aren't backups or are from a newer app`() {
        listOf("not json", """{"format":"something-else"}""", """{"format":"notisift-backup","version":99}""").forEach { bad ->
            try { Backup.decode(bad); fail("accepted $bad") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.isNotBlank()) }
        }
        try { Backup.decode("x".repeat(Backup.MAX_BYTES + 1)); fail("accepted a huge file") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `drops bad package names, unknown modes and actions, and caps text`() {
        val text = """{"format":"notisift-backup","version":1,
            "settings":{"threshold":7.0,"retentionDays":0,"myNames":"${"n".repeat(900)}"},
            "apps":[{"packageName":"com.ok.app","mode":"keep"},{"packageName":"../../etc","mode":"keep"},{"packageName":"com.bad.mode","mode":"explode"}],
            "corrections":[{"templateKey":"k1","action":"keep","category":"nonsense"},{"templateKey":"k2","action":"explode","category":"promo"}]}"""
        val f = Backup.decode(text)
        assertEquals(listOf("com.ok.app"), f.apps.map { it.packageName })
        assertEquals(listOf("k1"), f.corrections.map { it.templateKey })
        assertEquals("other", f.corrections.single().category)
        assertEquals(0.95, f.settings.threshold, 0.0)
        assertEquals(1, f.settings.retentionDays)
        assertEquals(500, f.settings.myNames.length)
    }
}
