package com.streamvault.app

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamvault.app.ui.StreamVaultAppScreen
import com.streamvault.app.viewmodel.StreamVaultViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let { highestMode ->
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = highestMode.modeId
                }
            }
        }

        setContent {
            val streamVaultViewModel: StreamVaultViewModel = viewModel()
            StreamVaultAppScreen(viewModel = streamVaultViewModel)
        }
    }
}
