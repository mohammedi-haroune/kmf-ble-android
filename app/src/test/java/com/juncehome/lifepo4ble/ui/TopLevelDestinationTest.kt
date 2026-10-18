package com.juncehome.lifepo4ble.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TopLevelDestinationTest {
    @Test
    fun destinationsStayInBottomBarOrder() {
        assertEquals(
            listOf(
                TopLevelDestination.Now,
                TopLevelDestination.Trends,
                TopLevelDestination.Device,
            ),
            TopLevelDestination.bottomBarItems,
        )
    }

    @Test
    fun nowIsTheStartDestination() {
        assertEquals(TopLevelDestination.Now, TopLevelDestination.startDestination)
    }

    @Test
    fun knownRoutesResolveToTheirDestination() {
        assertEquals(TopLevelDestination.Now, TopLevelDestination.fromRoute("now"))
        assertEquals(TopLevelDestination.Trends, TopLevelDestination.fromRoute("trends"))
        assertEquals(TopLevelDestination.Device, TopLevelDestination.fromRoute("device"))
    }

    @Test
    fun routesAreMatchedIgnoringCaseAndWhitespace() {
        assertEquals(TopLevelDestination.Trends, TopLevelDestination.fromRoute(" Trends "))
    }

    @Test
    fun legacyRoutesMapToTheTabThatReplacedThem() {
        assertEquals(TopLevelDestination.Now, TopLevelDestination.fromRoute("dashboard"))
        assertEquals(TopLevelDestination.Trends, TopLevelDestination.fromRoute("live"))
        assertEquals(TopLevelDestination.Trends, TopLevelDestination.fromRoute("history"))
        assertEquals(TopLevelDestination.Trends, TopLevelDestination.fromRoute("energy"))
        assertEquals(TopLevelDestination.Device, TopLevelDestination.fromRoute("diagnostic"))
    }

    @Test
    fun unknownRoutesFallBackToNow() {
        assertEquals(TopLevelDestination.Now, TopLevelDestination.fromRoute("not-a-route"))
        assertEquals(TopLevelDestination.Now, TopLevelDestination.fromRoute(""))
        assertEquals(TopLevelDestination.Now, TopLevelDestination.fromRoute(null))
    }

    @Test
    fun routesAndLegacyRoutesAreUnique() {
        val all = TopLevelDestination.entries.flatMap { listOf(it.route) + it.legacyRoutes }
        assertEquals(all.size, all.toSet().size)
    }
}
