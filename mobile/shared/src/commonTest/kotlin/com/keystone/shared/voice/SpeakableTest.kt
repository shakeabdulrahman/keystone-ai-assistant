package com.keystone.shared.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakableTest {

    @Test
    fun citationMarkersAreNotReadOut() {
        assertEquals("You can carry over 5 days.", speakable("You can carry over 5 days [1]."))
        assertEquals("It depends.", speakable("It depends [1, 3]."))
    }

    @Test
    fun markdownAndSymbolsBecomeWords() {
        assertEquals("Annual leave: 20 days", speakable("- **Annual leave:** 20 days"))
        assertEquals("Balance 14 days to 12 days", speakable("Balance 14 days → 12 days"))
        assertEquals("Mon 26 Oct to Tue 27 Oct", speakable("Mon 26 Oct – Tue 27 Oct"))
        assertEquals("IT and Payroll", speakable("IT & Payroll"))
        assertEquals("See the link on screen", speakable("See https://example.com/policy"))
    }

    @Test
    fun nothingLeftToSayIsEmpty() {
        assertEquals("", speakable("[2]."))
        assertEquals("", speakable("  **  "))
    }
}
