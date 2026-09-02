package com.juncehome.lifepo4ble.ui

enum class TopLevelDestination(
    val route: String,
    val label: String,
    val iconText: String,
) {
    Dashboard("dashboard", "Dashboard", "D"),
    Live("live", "Live", "L"),
    History("history", "History", "H"),
    Energy("energy", "Energy", "E"),
    Diagnostic("diagnostic", "Diagnostic", "G"),
    ;

    companion object {
        val startDestination: TopLevelDestination = Dashboard

        fun fromRoute(route: String?): TopLevelDestination =
            entries.firstOrNull { it.route == route } ?: startDestination

        val bottomBarItems: List<TopLevelDestination> = listOf(
            Dashboard,
            Live,
            History,
            Energy,
            Diagnostic,
        )
    }
}
