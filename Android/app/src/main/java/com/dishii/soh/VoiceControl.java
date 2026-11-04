package com.dishii.soh;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Alexa voice control on Fire TV.
 *
 * Fire TV doesn't let apps register their own phrases, but it does deliver Alexa's media
 * commands to the app in front through a MediaSession. They're mapped to the Enhancements menu:
 *
 *   "Alexa, pause" / "Alexa, stop" ..... open the Enhancements menu
 *   "Alexa, play" / "Alexa, resume" .... close it
 *   "Alexa, next" / "Alexa, previous" .. next / previous menu tab (opens the menu first)
 *   "Alexa, fast forward" / "rewind" ... next / previous sidebar section (opens the menu first)
 *
 * The phrase "Alexa, open enhancement menu" works through an Alexa Routine whose action pauses
 * this Fire TV (see FIRETV.md). "Alexa, select / move right / scroll down" are Fire TV's built-in
 * voice D-pad and need nothing here.
 */
final class VoiceControl {

    private static final String TAG = "SoH-Voice";
    // Report a fixed playhead so Alexa's "rewind"/"fast forward" arrive as seeks we can compare.
    private static final long PLAYHEAD_MS = 10L * 60 * 1000;
    // Home sends a pause ~150 ms before the activity pauses; wait to tell the two apart.
    private static final long PAUSE_SETTLE_MS = 350;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MediaSession session;
    private final AudioManager audio;
    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> { };
    private boolean resumed = false;

    VoiceControl(MainActivity activity) {
        this.activity = activity;
        this.audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);

        session = new MediaSession(activity, "ShipOfHarkinian");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { Log.i(TAG, "pause"); openMenuUnlessLeaving(); }
            @Override public void onStop() { Log.i(TAG, "stop"); openMenuUnlessLeaving(); }
            @Override public void onPlay() { Log.i(TAG, "play"); activity.requestMenu(MainActivity.MENU_CLOSE); }
            @Override public void onSkipToNext() { Log.i(TAG, "next"); activity.requestMenu(MainActivity.MENU_NEXT_TAB); }
            @Override public void onSkipToPrevious() { Log.i(TAG, "previous"); activity.requestMenu(MainActivity.MENU_PREV_TAB); }
            @Override public void onFastForward() { Log.i(TAG, "fast forward"); activity.requestMenu(MainActivity.MENU_NEXT_SECTION); }
            @Override public void onRewind() { Log.i(TAG, "rewind"); activity.requestMenu(MainActivity.MENU_PREV_SECTION); }
            @Override public void onSeekTo(long pos) {
                Log.i(TAG, "seek " + pos);
                // "Alexa, restart" seeks to 0: ignore. Otherwise compare against the fixed playhead.
                if (pos > PLAYHEAD_MS) activity.requestMenu(MainActivity.MENU_NEXT_SECTION);
                else if (pos > 0 && pos < PLAYHEAD_MS) activity.requestMenu(MainActivity.MENU_PREV_SECTION);
                publishState(); // snap the playhead back
            }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                // Physical remote keys are handled by RemoteInput while the game has focus; anything
                // routed here (e.g. adb "media dispatch") goes through the normal transport mapping.
                KeyEvent key = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (key != null) Log.i(TAG, "media button " + key.getKeyCode());
                return super.onMediaButtonEvent(mediaButtonIntent);
            }
        }, handler);
        publishState();
    }

    // Mirror the menu as play state: menu open = "paused", so "Alexa, play" is meaningful to Fire OS.
    private boolean lastMenuOpen = false;
    private final Runnable syncState = new Runnable() {
        @Override public void run() {
            boolean open = activity.isMenuOpen();
            if (open != lastMenuOpen) {
                lastMenuOpen = open;
                publishState();
            }
            if (resumed) handler.postDelayed(this, 400);
        }
    };

    private void publishState() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(lastMenuOpen ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING,
                        PLAYHEAD_MS, 0f) // speed 0: playhead never moves
                .build());
    }

    private void openMenuUnlessLeaving() {
        handler.postDelayed(() -> {
            if (resumed) activity.requestMenu(MainActivity.MENU_OPEN);
        }, PAUSE_SETTLE_MS);
    }

    /** Only the app in front should take "Alexa, pause". */
    @SuppressWarnings("deprecation")
    void onResume() {
        resumed = true;
        if (audio != null) {
            // Hold audio focus while in front so Fire OS treats this as the active media app.
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
        session.setActive(true);
        handler.removeCallbacks(syncState);
        handler.post(syncState);
    }

    @SuppressWarnings("deprecation")
    void onPause() {
        resumed = false;
        handler.removeCallbacks(syncState);
        session.setActive(false);
        if (audio != null) audio.abandonAudioFocus(focusListener);
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        session.release();
    }
}
