package com.example.ptt

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import com.example.ptt.setup.Route
import com.example.ptt.setup.SetupApp
import com.example.ptt.setup.SetupViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Hosts the setup screens. It deliberately does not use showWhenLocked/turnScreenOn: nothing here
 * may appear over the lock screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val viewModel: SetupViewModel = hiltViewModel()
            val route by viewModel.route.collectAsState()
            // Enrollment screens show and accept QR text: no screenshots or recents thumbnails. The flag is
            // already on from onCreate, so the first frame is covered; it is cleared once the route is known
            // and is not the enrollment screen.
            LaunchedEffect(route) {
                if (route != null && route !is Route.Enroll) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            // Route (and sync) each time the app returns to the foreground, not just once per activity: a 401
            // or the re-enroll flag set while Home was showing must be acted on.
            LifecycleStartEffect(viewModel) {
                viewModel.onStart()
                onStopOrDispose { }
            }
            SetupApp(viewModel)
        }
    }
}
