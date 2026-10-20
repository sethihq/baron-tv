package com.barontv.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.barontv.remote.tv.TvKey
import com.barontv.remote.tv.TvRemote
import com.barontv.remote.tv.TvState
import com.barontv.remote.ui.RemoteApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    // Placeholder until the TV connection lands.
    private val remote: TvRemote = object : TvRemote {
        override val state: StateFlow<TvState> = MutableStateFlow(TvState.Searching)
        override fun send(key: TvKey) {}
        override fun retry() {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RemoteApp(remote) }
    }

    override fun onStart() {
        super.onStart()
        remote.start()
    }

    override fun onStop() {
        remote.stop()
        super.onStop()
    }
}
