package com.nascon.league

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nascon.league.di.LocalAppContainer
import com.nascon.league.ui.navigation.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as App).container
        // Process death: the link that was waiting comes back with the saved state, then the launch intent is offered.
        container.deepLinks.restore(savedInstanceState?.getString(STATE_PENDING_LINK))
        // Only a fresh launch carries a new link; a recreated activity (process death) already restored its own state.
        if (savedInstanceState == null) offerLink(intent)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.connectivity.onForeground()
            override fun onStop(owner: LifecycleOwner) = container.connectivity.onBackground()
        })
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AppRoot()
            }
        }
    }

    /** A link delivered while the app is already running (singleTask). Navigates on the existing task. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (application as App).container.deepLinks.save()?.let { outState.putString(STATE_PENDING_LINK, it) }
    }

    private fun offerLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val container = (application as App).container
        if (container.deepLinks.offer(intent.dataString)) container.deepLinkSignal.value += 1
    }

    private companion object {
        const val STATE_PENDING_LINK = "pending_deep_link"
    }
}
