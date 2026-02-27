package com.sidespot

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidespot.auth.AuthManager
import com.sidespot.bridge.NativeBridge
import com.sidespot.settings.SettingsManager
import com.sidespot.ui.Routes
import com.sidespot.ui.SidespotNavigation
import com.sidespot.ui.SidespotTheme
import com.sidespot.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var playerViewModel: PlayerViewModel? = null
    private lateinit var authManager: AuthManager
    private lateinit var settingsManager: SettingsManager

    var currentRoute: String? = null
    var onNowPlayingToggleRequested: (() -> Unit)? = null
    var onBackRequested: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authManager = AuthManager(this)
        settingsManager = SettingsManager(this)

        // Push initial config to native before any connect
        settingsManager.pushConfigToNative()

        // Handle deep link on cold start
        handleAuthCallback(intent)

        setContent {
            SidespotTheme {
                val vm: PlayerViewModel = viewModel()
                playerViewModel = vm
                vm.initPlatform(this@MainActivity)
                vm.initApi(authManager)

                SidespotNavigation(
                    playerViewModel = vm,
                    authManager = authManager,
                    settingsManager = settingsManager,
                    mainActivity = this@MainActivity,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "sidespot" && uri.host == "callback") {
            val code = uri.getQueryParameter("code") ?: return
            lifecycleScope.launch {
                authManager.exchangeCode(code)
            }
        }
    }

    private fun adjustVolume(up: Boolean): Boolean {
        val step = 65535 / 20
        val current = NativeBridge.playerGetVolume()
        val newVol = if (up) (current + step).coerceAtMost(65535)
        else (current - step).coerceAtLeast(0)
        NativeBridge.playerSetVolume(newVol)
        playerViewModel?.onVolumeChanged(newVol)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d("Sundial", "keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
        }

        // Tab key — intercept before Compose consumes it for focus traversal
        if (event.keyCode == KeyEvent.KEYCODE_TAB) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                onNowPlayingToggleRequested?.invoke()
            }
            return true
        }

        // Context-sensitive center button: Play/Pause on Now Playing, DPAD_CENTER elsewhere
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (currentRoute == Routes.NOW_PLAYING) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val vm = playerViewModel ?: return true
                    if (vm.uiState.value.isPlaying) vm.pause() else vm.play()
                }
                return true
            } else {
                // Translate to DPAD_CENTER so Compose focus system clicks the focused item
                val translated = KeyEvent(
                    event.downTime, event.eventTime, event.action,
                    KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, event.metaState,
                    event.deviceId, event.scanCode, event.flags, event.source,
                )
                return super.dispatchKeyEvent(translated)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // Hardware volume keys — always adjust volume
            KeyEvent.KEYCODE_VOLUME_UP -> adjustVolume(up = true)
            KeyEvent.KEYCODE_VOLUME_DOWN -> adjustVolume(up = false)

            // Media skip keys
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playerViewModel?.next(); true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playerViewModel?.previous(); true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                playerViewModel?.play(); true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                playerViewModel?.pause(); true
            }

            // DPAD_LEFT — navigate back
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                onBackRequested?.invoke(); true
            }

            // D-pad up/down — volume on Now Playing, focus traversal elsewhere
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentRoute == Routes.NOW_PLAYING) adjustVolume(up = true)
                else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentRoute == Routes.NOW_PLAYING) adjustVolume(up = false)
                else super.onKeyDown(keyCode, event)
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }
}
