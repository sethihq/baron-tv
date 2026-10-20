package com.barontv.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.barontv.remote.tv.TvRemote

/** The whole app for now: the Remote screen. (Placeholder until the remote screen lands.) */
@Composable
fun RemoteApp(remote: TvRemote) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Remote", color = Color.White)
    }
}
