package me.ynk.moredisplay.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    HOME("home", "主页", Icons.Filled.Home),
    DISPLAYS("displays", "显示器", Icons.Filled.List),
    POLICIES("policies", "策略", Icons.Filled.Settings);

    companion object {
        fun fromRoute(route: String?): Screen =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}
