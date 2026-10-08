package com.rshop.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {

    @Test
    fun `next moves right`() {
        assertEquals(TopLevelDestination.Store, TopLevelDestination.Home.cycle(1))
    }

    @Test
    fun `previous from first wraps to last`() {
        assertEquals(TopLevelDestination.Settings, TopLevelDestination.Home.cycle(-1))
    }

    @Test
    fun `next from last wraps to first`() {
        assertEquals(TopLevelDestination.Home, TopLevelDestination.Settings.cycle(1))
    }

    @Test
    fun `full cycle returns to start`() {
        TopLevelDestination.entries.forEach { tab ->
            assertEquals(tab, tab.cycle(TopLevelDestination.entries.size))
        }
    }
}
