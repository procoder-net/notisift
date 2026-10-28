package io.github.notisift.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRulesTest {
    @Test fun `chat name drops message counts, senders and direction marks`() {
        assertEquals("Stocks Club #1", ChatRules.chatName("Stocks Club #1 (123 messages): ~ SMR"))
        assertEquals("Hikers", ChatRules.chatName("Hikers (2 messages): Sam"))
        assertEquals("Hikers", ChatRules.chatName("Hikers: Sam"))
        assertEquals("Alex Rivera", ChatRules.chatName("Alex Rivera (3 messages)"))
        assertEquals("Shaheer Arabic", ChatRules.chatName("\u200e\u202aShaheer Arabic\u202c\u200e"))
    }

    @Test fun `key ignores case and the per-message suffix`() {
        assertEquals(ChatRules.key("com.whatsapp", "Hikers (9 messages): Sam"), ChatRules.key("com.whatsapp", "hikers: Jo"))
    }

    @Test fun `rules survive a round trip`() {
        val rows = listOf(ChatRule("com.whatsapp", "Hikers", ChatMode.MENTIONS), ChatRule("com.whatsapp", "Mom", ChatMode.ALWAYS))
        assertEquals(rows, ChatRules.rows(ChatRules.format(rows)))
        assertEquals(ChatMode.MENTIONS, ChatRules.parse(ChatRules.format(rows))[ChatRules.key("com.whatsapp", "hikers (3 messages): Jo")])
        assertEquals(emptyMap<String, ChatMode>(), ChatRules.parse("garbage\nmore"))
    }

    @Test fun `with sets, replaces and removes a chat choice`() {
        val start = listOf(ChatRule("com.whatsapp", "Hikers", ChatMode.MENTIONS))
        val muted = ChatRules.with(start, listOf("com.whatsapp" to "Hikers (4 messages): Sam"), ChatMode.MUTE)
        assertEquals(listOf(ChatRule("com.whatsapp", "Hikers", ChatMode.MUTE)), muted)
        assertEquals(emptyList<ChatRule>(), ChatRules.with(muted, listOf("com.whatsapp" to "Hikers"), null))
    }

    @Test fun `mentions by name, by at-you and by reply wording`() {
        assertTrue(ChatRules.mentions("@Sam are you coming?", listOf("Sam Lee")))
        assertTrue(ChatRules.mentions("hey @sam lee", listOf("Sam Lee")))
        assertTrue(ChatRules.mentions("Jo replied to you: ok", emptyList()))
        assertFalse(ChatRules.mentions("Fresh fish ready at 6:45pm", listOf("Sam Lee")))
        assertFalse(ChatRules.mentions("email me at team@samples.com", listOf("Sam")))
    }

    @Test fun `group-looking titles`() {
        assertTrue(ChatRules.looksLikeGroup("Stocks Club (123 messages): ~ SMR", ""))
        assertTrue(ChatRules.looksLikeGroup("Hikers: Sam", ""))
        assertTrue(ChatRules.looksLikeGroup("Hikers", "~ Sam: hi"))
        assertFalse(ChatRules.looksLikeGroup("Alex Rivera", "Running late"))
        assertFalse(ChatRules.looksLikeGroup("Alex Rivera (3 messages)", "Running late"))
    }

    @Test fun `busy groups need several messages today and no choice yet`() {
        val msgs = List(6) { ChatMessage("com.whatsapp", "WhatsApp", "Stocks Club ($it messages): ~ SMR", "tip") } +
            List(2) { ChatMessage("com.whatsapp", "WhatsApp", "Hikers: Sam", "hi") } +
            List(9) { ChatMessage("com.whatsapp", "WhatsApp", "Alex Rivera", "hi") } +
            List(7) { ChatMessage("com.whatsapp", "WhatsApp", "Muted Group: Jo", "x") }
        val chosen = mapOf(ChatRules.key("com.whatsapp", "Muted Group") to ChatMode.MUTE)
        val busy = ChatRules.busyGroups(msgs, chosen, minMessages = 5)
        assertEquals(listOf("Stocks Club"), busy.map { it.chat })
        assertEquals(6, busy.single().count)
    }
}
