package com.barontv.home.media

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService

/**
 * Notification access, where the TV allows it, lets the home screen read every app's media session; this
 * service holds it and hands the sessions to a SessionFollower. Low-RAM Android (this TV: ro.config.low_ram
 * is true) has no notification access at all, so here it never starts and SessionTokens does the job.
 */
class NowPlayingListener : NotificationListenerService() {

    private var follower: SessionFollower? = null
    private var sessions: MediaSessionManager? = null
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> follower?.follow(list) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (follower != null) return
        val f = SessionFollower(this)
        follower = f
        f.start()
        val component = ComponentName(this, NowPlayingListener::class.java)
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component, f.handler)
            sessions = msm
            f.follow(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Notification access was taken away while we started: nothing to follow.
        }
    }

    override fun onListenerDisconnected() {
        stop()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: RuntimeException) {
            // Already gone with the binding.
        }
        sessions = null
        follower?.stop()
        follower = null
    }
}
