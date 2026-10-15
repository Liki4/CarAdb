package dev.caradb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionPolicyTest {
    @Test
    fun idleDraftOffersConnectionWithoutAStopAction() {
        val actions = HomeActionPolicy.choose(false, false, false, true, true, false)
        assertEquals(PrimaryAction.CONNECT, actions.primary)
        assertTrue(actions.primaryEnabled)
        assertFalse(actions.secondaryDisconnect)
    }

    @Test
    fun firstSetupRespectsTheExistingLoadingGate() {
        val loading = HomeActionPolicy.choose(false, false, false, false, false, false)
        assertEquals(PrimaryAction.CONFIGURE, loading.primary)
        assertFalse(loading.primaryEnabled)
        assertTrue(HomeActionPolicy.choose(false, false, false, false, true, false).primaryEnabled)
    }

    @Test
    fun activeSessionCanStopEvenBeforeEditorsAreReady() {
        val actions = HomeActionPolicy.choose(true, false, false, false, false, true)
        assertEquals(PrimaryAction.DISCONNECT, actions.primary)
        assertTrue(actions.primaryEnabled)
        assertFalse(actions.secondaryDisconnect)
    }

    @Test
    fun stoppingDoesNotExposeAnotherConnect() {
        val actions = HomeActionPolicy.choose(true, true, false, true, false, false)
        assertEquals(PrimaryAction.DISCONNECT, actions.primary)
        assertFalse(actions.primaryEnabled)
        assertFalse(actions.secondaryDisconnect)
    }

    @Test
    fun failedDurableStopKeepsTheRetryStopProminent() {
        val actions = HomeActionPolicy.choose(false, false, true, true, true, true)
        assertEquals(PrimaryAction.DISCONNECT, actions.primary)
        assertTrue(actions.primaryEnabled)
    }

    @Test
    fun inactiveDurableIntentRetainsASecondaryDisarmAction() {
        val actions = HomeActionPolicy.choose(false, false, false, true, true, true)
        assertEquals(PrimaryAction.CONNECT, actions.primary)
        assertTrue(actions.primaryEnabled)
        assertTrue(actions.secondaryDisconnect)
    }
}
