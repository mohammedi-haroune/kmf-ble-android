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
                TopLevelDestination.Diagnostic,
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

    @Test
    fun knownRoutesResolveToTheirDestination() {
        assertEquals(
            TopLevelDestination.Live,
            TopLevelDestination.fromRoute("live"),
        )
    }

    @Test
    fun unknownRoutesFallBackToDashboard() {
        assertEquals(
            TopLevelDestination.Dashboard,
            TopLevelDestination.fromRoute("not-a-route"),
        )
        assertEquals(
            TopLevelDestination.Dashboard,
            TopLevelDestination.fromRoute(null),
        )
    }
}
