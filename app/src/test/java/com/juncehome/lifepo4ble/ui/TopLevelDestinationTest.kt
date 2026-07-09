package com.juncehome.lifepo4ble.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {
    @Test
    fun destinationsStayInBottomBarOrder() {
        assertEquals(
            listOf(
                TopLevelDestination.Dashboard,
                TopLevelDestination.Live,
                TopLevelDestination.History,
                TopLevelDestination.Energy,
                TopLevelDestination.Diagnostics,
            ),
            TopLevelDestination.bottomBarItems,
        )
    }

    @Test
    fun dashboardIsTheStartDestination() {
        assertEquals(
            TopLevelDestination.Dashboard,
            TopLevelDestination.startDestination,
        )
    }
}
