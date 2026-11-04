package com.dishii.soh;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes Ship of Harkinian's settings file (SOH/shipofharkinian.json) and the app's own
 * render-size preference, and defines the video presets offered in the launcher.
 */
final class SohConfig {

    static final String PREFS = "com.dishii.soh.prefs";
    static final String PREF_RENDER_HEIGHT = "renderHeight";
    static final String PREF_VIDEO_PRESET = "videoPreset";

    static File sohRoot() {
        return new File(Environment.getExternalStorageDirectory(), "SOH");
    }

    static File configFile() {
        return new File(sohRoot(), "shipofharkinian.json");
    }

    // ---- Video presets ----------------------------------------------------------------------

    static final class Preset {
        final String id, title, summary;
        final int renderHeight;      // 0 = native display height
        final int textureFilter;     // 0 = 3-point (N64), 1 = bilinear, 2 = none
        final int interpolationFps;  // 20 = original game speed
        final int lowResMode;        // 1 = N64-style 240p output
        final int msaa;

        Preset(String id, String title, String summary, int renderHeight, int textureFilter,
               int interpolationFps, int lowResMode, int msaa) {
            this.id = id; this.title = title; this.summary = summary; this.renderHeight = renderHeight;
            this.textureFilter = textureFilter; this.interpolationFps = interpolationFps;
            this.lowResMode = lowResMode; this.msaa = msaa;
        }
    }

    static final Preset[] PRESETS = {
        new Preset("performance", "Performance",
                "480p, smooth filtering, original 20 fps. For the weakest TVs and sticks.",
                480, 1, 20, 0, 1),
        new Preset("balanced", "Balanced (recommended)",
                "720p, smooth filtering, original 20 fps. Best fit for 2 GB Fire TVs.",
                720, 1, 20, 0, 1),
        new Preset("smooth", "Smooth",
                "720p with 30 fps frame interpolation. Smoother motion, more GPU work.",
                720, 1, 30, 0, 1),
        new Preset("quality", "Quality",
                "1080p, N64 3-point filtering, 30 fps. For Fire TV Cube / 4K Max class devices.",
                1080, 0, 30, 0, 1),
        new Preset("retro", "Retro N64",
                "Authentic 240p N64 look with 3-point filtering. Very light on the GPU.",
                720, 0, 20, 1, 1),
    };

    static Preset presetById(String id) {
        for (Preset p : PRESETS) if (p.id.equals(id)) return p;
        return null;
    }

    /** Writes the preset into the game's settings and the app's render-size preference. */
    static void applyPreset(Context ctx, Preset p) throws IOException, JSONException {
        JSONObject root = readConfig();
        JSONObject settings = child(child(root, "CVars"), "gSettings");
        settings.put("TextureFilter", p.textureFilter);
        settings.put("InterpolationFPS", p.interpolationFps);
        settings.put("LowResMode", p.lowResMode);
        settings.put("MSAAValue", p.msaa);
        writeConfig(root);
        prefs(ctx).edit()
                .putInt(PREF_RENDER_HEIGHT, p.renderHeight)
                .putString(PREF_VIDEO_PRESET, p.id)
                .apply();
    }

    /** Individual settings, for the fine-tune rows under the presets. */
    static void setGameSetting(String key, int value) throws IOException, JSONException {
        JSONObject root = readConfig();
        child(child(root, "CVars"), "gSettings").put(key, value);
        writeConfig(root);
    }

    static int getGameSetting(String key, int fallback) {
        try {
            JSONObject cvars = readConfig().optJSONObject("CVars");
            JSONObject settings = cvars == null ? null : cvars.optJSONObject("gSettings");
            return settings == null ? fallback : settings.optInt(key, fallback);
        } catch (IOException | JSONException e) {
            return fallback;
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- JSON helpers -----------------------------------------------------------------------

    static JSONObject readConfig() throws IOException, JSONException {
        File f = configFile();
        if (!f.exists()) {
            JSONObject root = new JSONObject();
            root.put("ConfigVersion", 3); // current SoH config version: skip the legacy migrations
            return root;
        }
        return new JSONObject(readText(f));
    }

    static void writeConfig(JSONObject root) throws IOException, JSONException {
        File f = configFile();
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(root.toString(4).getBytes(StandardCharsets.UTF_8));
        }
        f.delete();
        if (!tmp.renameTo(f)) throw new IOException("Couldn't save " + f);
    }

    static JSONObject child(JSONObject parent, String key) throws JSONException {
        JSONObject c = parent.optJSONObject(key);
        if (c == null) {
            c = new JSONObject();
            parent.put(key, c);
        }
        return c;
    }

    static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { // Files.readAllBytes needs API 26; minSdk is 24
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private SohConfig() { }
}
