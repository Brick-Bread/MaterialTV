package app.m3home;

import android.service.notification.NotificationListenerService;

/**
 * Needed only so Android lets MaterialTV read media sessions for the "Now playing" widget.
 * It ignores notifications. install.sh grants it with `cmd notification allow_listener`.
 */
public class MediaListener extends NotificationListenerService { }
