package com.keystone.android.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.keystone.android.ui.chat.ChatRoute
import com.keystone.android.ui.home.HomeRoute
import com.keystone.android.ui.navigation.ChatDestination
import com.keystone.android.ui.navigation.HomeDestination

/** App shell: owns navigation. Each screen owns its own Scaffold and top bar. */
@Composable
fun KeystoneApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(onOpenChat = { navController.navigate(ChatDestination) })
        }
        composable<ChatDestination> {
            ChatRoute(onBack = { navController.popBackStack() })
        }
    }
}
