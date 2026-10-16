package com.keystone.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.keystone.android.auth.AuthState
import com.keystone.android.ui.auth.SignInRoute
import com.keystone.android.ui.chat.ChatRoute
import com.keystone.android.ui.conversations.ConversationsRoute
import com.keystone.android.ui.document.DocumentRoute
import com.keystone.android.ui.home.HomeRoute
import com.keystone.android.ui.navigation.ChatDestination
import com.keystone.android.ui.navigation.ConversationsDestination
import com.keystone.android.ui.navigation.DocumentDestination
import com.keystone.android.ui.navigation.HomeDestination

/**
 * App shell. Signed-out users only ever see the sign-in screen; the signed-in navigation
 * graph is created fresh for each session, so no screen from one user survives into the next.
 */
@Composable
fun KeystoneApp(appViewModel: AppViewModel = hiltViewModel()) {
    val authState by appViewModel.authState.collectAsStateWithLifecycle()
    when (val state = authState) {
        AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        AuthState.SignedOut -> SignInRoute()
        is AuthState.SignedIn -> SignedInApp(email = state.email, onSignOut = appViewModel::signOut)
    }
}

@Composable
private fun SignedInApp(email: String?, onSignOut: () -> Unit) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(
                userEmail = email,
                onOpenChat = { navController.navigate(ChatDestination()) },
                onOpenHistory = { navController.navigate(ConversationsDestination) },
                onSignOut = onSignOut,
            )
        }
        composable<ConversationsDestination> {
            ConversationsRoute(
                onBack = { navController.popBackStack() },
                onOpenConversation = { id -> navController.navigate(ChatDestination(conversationId = id)) },
                onNewConversation = { navController.navigate(ChatDestination()) },
            )
        }
        composable<ChatDestination> {
            ChatRoute(
                onBack = { navController.popBackStack() },
                onOpenSource = { citation ->
                    navController.navigate(DocumentDestination(citation.documentId, citation.sectionIndex))
                },
            )
        }
        composable<DocumentDestination> {
            DocumentRoute(onBack = { navController.popBackStack() })
        }
    }
}
