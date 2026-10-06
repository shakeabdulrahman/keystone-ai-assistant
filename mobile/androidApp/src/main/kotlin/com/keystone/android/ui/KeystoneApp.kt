package com.keystone.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.keystone.android.R
import com.keystone.android.ui.home.HomeRoute

/** App shell. Navigation and bottom bar arrive with the chat and history screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoneApp() {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        HomeRoute(modifier = Modifier.padding(innerPadding))
    }
}
