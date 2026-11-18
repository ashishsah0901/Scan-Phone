package com.ashish.scanphone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

enum class AppTab(val label: String) {
    SCAN("Scan"),
    THREATS("Threats"),
    RECOVER("Recover"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(requestedTab: AppTab? = null, onRequestedTabShown: () -> Unit = {}) {
    var selectedTab by rememberSaveable { androidx.compose.runtime.mutableStateOf(AppTab.SCAN) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            selectedTab = requestedTab
            onRequestedTabShown()
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val recoverViewModel: RecoverViewModel = viewModel()
    val threatViewModel: ThreatViewModel = viewModel()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                AppTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                    )
                }
            }

            androidx.compose.runtime.LaunchedEffect(selectedTab) {
                if (selectedTab == AppTab.RECOVER) recoverViewModel.refresh()
            }

            when (selectedTab) {
                AppTab.SCAN -> ScanScreen()
                AppTab.THREATS -> ThreatScreen(
                    viewModel = threatViewModel,
                    onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )

                AppTab.RECOVER -> RecoverScreen(
                    viewModel = recoverViewModel,
                    onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                )
            }
        }
    }
}
