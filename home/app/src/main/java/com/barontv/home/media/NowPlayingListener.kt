package com.barontv.home.media

import android.service.notification.NotificationListenerService

/**
 * Notification access is what lets the home screen read other apps' media sessions; this service holds
 * it and publishes to NowPlayingHub. (Placeholder: the media part replaces this.)
 */
class NowPlayingListener : NotificationListenerService()
