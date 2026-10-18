package com.juncehome.lifepo4ble.ui

/**
 * Bottom navigation tabs from docs/design/README.md "Navigation".
 *
 * [legacyRoutes] keeps old launch extras (e.g. `make live`, `--es ... dashboard`) working after
 * the five-tab layout collapsed into Now / Trends / Device.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val legacyRoutes: Set<String> = emptySet(),
) {
    Now("now", "Now", legacyRoutes = setOf("dashboard")),
    Trends("trends", "Trends", legacyRoutes = setOf("live", "history", "energy")),
    Device("device", "Device", legacyRoutes = setOf("diagnostic")),
    ;

    companion object {
        val startDestination: TopLevelDestination = Now

        val bottomBarItems: List<TopLevelDestination> = listOf(Now, Trends, Device)

        /** Resolves a route or legacy route (case-insensitive); unknown or null falls back to [Now]. */
        fun fromRoute(route: String?): TopLevelDestination {
            val normalized = route?.trim()?.lowercase() ?: return startDestination
            return entries.firstOrNull { it.route == normalized || normalized in it.legacyRoutes }
                ?: startDestination
        }
    }
}
