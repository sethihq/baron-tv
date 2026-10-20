package com.barontv.home.media

import android.app.Notification
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession

/**
 * What's playing on a TV without notification access (low-RAM Android 9, this TV). A music app shows a
 * media notification while it plays, and that notification carries its session's token; the Input key's
 * accessibility service sees every notification posted, and a MediaController made from a token needs no
 * permission at all. So each app's latest token is kept here and the sessions are followed as usual.
 * Main thread only (accessibility events arrive there).
 */
object SessionTokens {
    private var follower: SessionFollower? = null
    private val byPackage = LinkedHashMap<String, MediaController>()

    /** A notification from [pkg] was posted or updated: follow its media session if it has one. */
    fun onNotification(context: Context, pkg: String, notification: Notification) {
        @Suppress("DEPRECATION")
        val token = notification.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) ?: return
        if (byPackage[pkg]?.sessionToken == token) return
        val app = context.applicationContext
        val f = follower ?: SessionFollower(app).also {
            follower = it
            it.start()
        }
        byPackage.remove(pkg)
        byPackage[pkg] = MediaController(app, token)
        f.follow(byPackage.values.toList())
    }

    /** The accessibility service went away: let the sessions go. */
    fun release() {
        follower?.stop()
        follower = null
        byPackage.clear()
    }
}
