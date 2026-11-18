package com.ashish.scanphone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ashish.scanphone.ui.AppScreen
import com.ashish.scanphone.ui.AppTab
import com.ashish.scanphone.ui.theme.ScanPhoneTheme

class MainActivity : ComponentActivity() {
    private var requestedTab by mutableStateOf<AppTab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requestedTab = tabFrom(intent)
        setContent {
            ScanPhoneTheme {
                AppScreen(
                    requestedTab = requestedTab,
                    onRequestedTabShown = { requestedTab = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = tabFrom(intent)
    }

    companion object {
        private const val EXTRA_TAB = "tab"

        fun openTabIntent(context: Context, tab: AppTab): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TAB, tab.name)

        private fun tabFrom(intent: Intent?): AppTab? {
            val name = intent?.getStringExtra(EXTRA_TAB) ?: return null
            return AppTab.entries.firstOrNull { it.name == name }
        }
    }
}
