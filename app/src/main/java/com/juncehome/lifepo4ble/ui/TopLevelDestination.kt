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
    Diagnostics("diagnostics", "Diagnostics", "G"),
    ;

    companion object {
        val startDestination: TopLevelDestination = Dashboard

        val bottomBarItems: List<TopLevelDestination> = listOf(
            Dashboard,
            Live,
            History,
            Energy,
            Diagnostics,
        )
    }
}
