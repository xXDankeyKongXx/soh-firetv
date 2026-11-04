package com.dishii.soh;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * Plays Ocarina of Time with the Fire TV remote alone by driving the same SDL virtual controller
 * the touch overlay uses. A real gamepad keeps working alongside it (both feed player 1).
 *
 *   D-pad ............ move (left stick). Also moves through the Enhancements menu.
 *   Select (center) .. A
 *   Back ............. B
 *   Play/Pause ....... Start
 *   Rewind ........... Z (hold to target)
 *   Fast-forward ..... R (shield)
 *   Menu (≡) tap ..... arm the C-buttons: the next D-pad press is C-up/left/down/right
 *   Menu (≡) hold .... open/close the Enhancements menu
 *
 * While the Enhancements menu is open the remote switches to menu controls:
 *   D-pad ............ move the menu cursor (sent as controller D-pad, which the menu navigates with)
 *   Select / Back .... A (choose) / B (back; closes the menu when there's nothing to back out of)
 *   Play/Pause ....... next tab (RB)
 *   Rewind / FF ...... previous / next sidebar section (LT / RT)
 *   Menu (≡) tap ..... close the menu
 */
final class RemoteInput {

    private static final short TILT = 32767;
    // Virtual pad buttons (SDL default virtual game controller layout)
    static final int PAD_LEFT_SHOULDER = 9;
    static final int PAD_RIGHT_SHOULDER = 10;
    private static final int[] PAD_DPAD = { ControllerButtons.BUTTON_DPAD_UP, ControllerButtons.BUTTON_DPAD_DOWN,
                                            ControllerButtons.BUTTON_DPAD_LEFT, ControllerButtons.BUTTON_DPAD_RIGHT };
    private static final long PULSE_MS = 120;
    private static final long LONG_PRESS_MS = 600;

    private final MainActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView cIndicator;

    private boolean up, down, left, right;   // held D-pad directions (for the stick)
    private final boolean[] dpadAsButton = new boolean[4]; // directions pressed while the menu was open
    private boolean playAsTab = false;
    private boolean cArmed = false;
    private boolean menuLongFired = false;
    private final Runnable menuLongPress;

    RemoteInput(MainActivity activity) {
        this.activity = activity;
        this.menuLongPress = () -> {
            menuLongFired = true;
            setCArmed(false);
            activity.requestMenu(MainActivity.MENU_TOGGLE);
        };
    }

    /** Small "C" badge in the corner while the C-buttons are armed. */
    void attachIndicator(ViewGroup root) {
        cIndicator = new TextView(activity);
        cIndicator.setText("C ▲◀▼▶");
        cIndicator.setTextColor(Color.rgb(255, 214, 64));
        cIndicator.setTextSize(22);
        cIndicator.setPadding(24, 12, 24, 12);
        cIndicator.setBackgroundColor(Color.argb(150, 0, 0, 0));
        cIndicator.setVisibility(android.view.View.GONE);
        // SDL's content view is a RelativeLayout, so gravity has to be expressed as alignment rules.
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_END);
        lp.setMargins(0, 48, 48, 0);
        root.addView(cIndicator, lp);
    }

    /** True for the TV remote; false for gamepads, which SDL already handles. */
    static boolean isRemoteEvent(KeyEvent e) {
        InputDevice dev = e.getDevice();
        int sources = dev != null ? dev.getSources() : e.getSource();
        if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return false;
        if ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        return true;
    }

    /** Returns true if the key was consumed. */
    boolean handle(KeyEvent e) {
        if (!isRemoteEvent(e)) return false;

        boolean isDown = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean isUp = e.getAction() == KeyEvent.ACTION_UP;
        boolean first = isDown && e.getRepeatCount() == 0;

        // If the menu opened while the D-pad was held as the stick, let go of the stick so the
        // menu doesn't scroll on its own.
        if (first && (up || down || left || right) && activity.isMenuOpen()) {
            up = down = left = right = false;
            activity.setAxis(ControllerButtons.AXIS_LX, (short) 0);
            activity.setAxis(ControllerButtons.AXIS_LY, (short) 0);
        }

        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:    return direction(first, isUp, 0);
            case KeyEvent.KEYCODE_DPAD_DOWN:  return direction(first, isUp, 1);
            case KeyEvent.KEYCODE_DPAD_LEFT:  return direction(first, isUp, 2);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return direction(first, isUp, 3);

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (first) setCArmed(false);
                return button(first, isUp, ControllerButtons.BUTTON_A);
            case KeyEvent.KEYCODE_BACK:
                if (first && cArmed) { setCArmed(false); return true; } // Back cancels the C layer
                return button(first, isUp, ControllerButtons.BUTTON_B);
            // Discrete pause/play come from Alexa ("Alexa, pause" / "play"), not the remote's
            // Play/Pause button, so they control the Enhancements menu.
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (first) activity.requestMenu(MainActivity.MENU_OPEN);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (first) activity.requestMenu(MainActivity.MENU_CLOSE);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                // In the menu, Play/Pause is "next tab"; remember which so the release matches.
                if (first) playAsTab = activity.isMenuOpen();
                return button(first, isUp, playAsTab ? PAD_RIGHT_SHOULDER : ControllerButtons.BUTTON_START);
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return button(first, isUp, ControllerButtons.AXIS_RT);   // Z (left trigger)
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return button(first, isUp, ControllerButtons.BUTTON_RB); // R (right trigger)

            case KeyEvent.KEYCODE_MENU:
                if (first && activity.isMenuOpen()) {
                    menuLongFired = true; // swallow the release
                    activity.requestMenu(MainActivity.MENU_CLOSE);
                    return true;
                }
                if (first) {
                    menuLongFired = false;
                    handler.postDelayed(menuLongPress, LONG_PRESS_MS);
                } else if (isUp) {
                    handler.removeCallbacks(menuLongPress);
                    if (!menuLongFired) setCArmed(!cArmed);
                }
                return true;
            default:
                return false;
        }
    }

    private boolean button(boolean first, boolean isUp, int id) {
        if (first) activity.setButton(id, true);
        else if (isUp) activity.setButton(id, false);
        return true;
    }

    private boolean direction(boolean first, boolean isUp, int dir) {
        // Menu open: the D-pad drives the menu cursor as controller D-pad buttons.
        if (first && activity.isMenuOpen()) {
            dpadAsButton[dir] = true;
            activity.setButton(PAD_DPAD[dir], true);
            return true;
        }
        if (isUp && dpadAsButton[dir]) {
            dpadAsButton[dir] = false;
            activity.setButton(PAD_DPAD[dir], false);
            return true;
        }
        if (first && cArmed) {
            setCArmed(false);
            pulseC(dir);
            return true;
        }
        if (first) setHeld(dir, true);
        else if (isUp) setHeld(dir, false);
        return true;
    }

    private void setHeld(int dir, boolean held) {
        switch (dir) {
            case 0: up = held; break;
            case 1: down = held; break;
            case 2: left = held; break;
            default: right = held; break;
        }
        short x = (short) ((right ? TILT : 0) - (left ? TILT : 0));
        short y = (short) ((down ? TILT : 0) - (up ? TILT : 0));
        activity.setAxis(ControllerButtons.AXIS_LX, x);
        activity.setAxis(ControllerButtons.AXIS_LY, y);
    }

    // C-buttons live on the right stick, same convention as the touch overlay.
    private void pulseC(int dir) {
        final int axis = (dir == 0 || dir == 1) ? ControllerButtons.AXIS_RY : ControllerButtons.AXIS_RX;
        final short value = (dir == 0 || dir == 2) ? Short.MIN_VALUE : Short.MAX_VALUE;
        activity.setAxis(axis, value);
        handler.postDelayed(() -> activity.setAxis(axis, (short) 0), PULSE_MS);
    }

    void pulseButton(int id) {
        activity.setButton(id, true);
        handler.postDelayed(() -> activity.setButton(id, false), PULSE_MS);
    }

    private void setCArmed(boolean armed) {
        cArmed = armed;
        if (cIndicator != null) {
            cIndicator.setVisibility(armed ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    /** Release everything, e.g. when the app loses focus mid-press. */
    void releaseAll() {
        handler.removeCallbacksAndMessages(null);
        up = down = left = right = false;
        activity.setAxis(ControllerButtons.AXIS_LX, (short) 0);
        activity.setAxis(ControllerButtons.AXIS_LY, (short) 0);
        activity.setAxis(ControllerButtons.AXIS_RX, (short) 0);
        activity.setAxis(ControllerButtons.AXIS_RY, (short) 0);
        int[] buttons = { ControllerButtons.BUTTON_A, ControllerButtons.BUTTON_B, ControllerButtons.BUTTON_START,
                          ControllerButtons.BUTTON_BACK, ControllerButtons.AXIS_RT, ControllerButtons.BUTTON_RB,
                          PAD_LEFT_SHOULDER, PAD_RIGHT_SHOULDER };
        for (int b : buttons) activity.setButton(b, false);
        for (int i = 0; i < 4; i++) {
            if (dpadAsButton[i]) activity.setButton(PAD_DPAD[i], false);
            dpadAsButton[i] = false;
        }
        playAsTab = false;
        setCArmed(false);
    }
}
