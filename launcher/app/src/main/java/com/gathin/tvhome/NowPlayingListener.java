package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the home screen may see which app is playing media (Android requires notification access for
 * MediaSessionManager.getActiveSessions). It reads no notifications. Granted once with:
 * cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.NowPlayingListener
 */
public class NowPlayingListener extends NotificationListenerService {
}
