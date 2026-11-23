package app.m3home;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.List;

/** "Now playing": the title of whatever an app is playing, if it shares it with Android. */
final class NowPlaying {
    private NowPlaying() {}

    /** "Title · Artist", or null when nothing is playing or access isn't granted. */
    static String current(Context c) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (m == null) return null;
            List<MediaController> sessions = m.getActiveSessions(new ComponentName(c, MediaListener.class));
            for (MediaController mc : sessions) {
                PlaybackState st = mc.getPlaybackState();
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                MediaMetadata md = mc.getMetadata();
                if (md == null) continue;
                String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (title == null || title.isEmpty()) continue;
                return artist == null || artist.isEmpty() ? title : title + " · " + artist;
            }
        } catch (SecurityException | IllegalStateException ignored) {
            // Not granted yet: install.sh grants it.
        }
        return null;
    }
}
