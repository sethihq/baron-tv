package com.barontv.remote

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.barontv.remote.tv.CloudWalkerRemote
import com.barontv.remote.tv.HttpTvBridge
import com.barontv.remote.tv.TvRemote
import com.barontv.remote.ui.RemoteApp

class MainActivity : ComponentActivity() {
    private val remote: TvRemote by lazy { sharedRemote(applicationContext) }
    private val bridge: HttpTvBridge by lazy { sharedBridge(remote) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RemoteApp(remote, bridge) }
    }

    // Only while the app is on screen: no searching or checking in the background.
    override fun onStart() {
        super.onStart()
        remote.start()
        bridge.start()
    }

    override fun onStop() {
        bridge.stop()
        remote.stop()
        super.onStop()
    }
}

// One remote for the whole app, so turning the phone round doesn't look for the TV all over again.
private var shared: TvRemote? = null

private fun sharedRemote(context: Context): TvRemote = shared ?: CloudWalkerRemote(context).also { shared = it }

// The TV's new home, beyond key presses (home/BRIDGE.md), shared the same way.
private var sharedBridge: HttpTvBridge? = null

private fun sharedBridge(remote: TvRemote): HttpTvBridge = sharedBridge ?: HttpTvBridge(remote).also { sharedBridge = it }
