package com.dishii.soh;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes Ship of Harkinian's settings file (SOH/shipofharkinian.json) and the app's own
 * render-size preference, and defines the video presets offered in the launcher.
 *
 * The file is edited as a tree that keeps every number exactly as written. This matters: the game
 * loads 1.0 as a float setting but 1 as an integer one, and reading a float setting that was saved
 * as an integer silently returns its default. (org.json would rewrite 1.0 as 1.)
 */
final class SohConfig {

    static final String PREFS = "com.dishii.soh.prefs";
    static final String PREF_RENDER_HEIGHT = "renderHeight";
    static final String PREF_VIDEO_PRESET = "videoPreset";
    static final long LOW_RAM_BYTES = 2560L * 1024 * 1024;

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

    static String presetLabel(Context ctx) {
        String id = prefs(ctx).getString(PREF_VIDEO_PRESET, "");
        Preset p = presetById(id);
        if (p != null) return p.title;
        return "custom".equals(id) ? "Custom" : "Automatic";
    }

    /** Writes the preset into the game's settings and the app's render-size preference. */
    static void applyPreset(Context ctx, Preset p) throws IOException {
        Map<String, Object> root = readConfig();
        Map<String, Object> s = child(child(root, "CVars"), "gSettings");
        s.put("TextureFilter", new Num(p.textureFilter));
        s.put("InterpolationFPS", new Num(p.interpolationFps));
        s.put("LowResMode", new Num(p.lowResMode));
        s.put("MSAAValue", new Num(p.msaa));
        writeConfig(root);
        prefs(ctx).edit()
                .putInt(PREF_RENDER_HEIGHT, p.renderHeight)
                .putString(PREF_VIDEO_PRESET, p.id)
                .apply();
    }

    /** Fills in integer gSettings values. Only missing keys unless force. Returns true if written. */
    static boolean applyIntSettings(Map<String, Integer> values, boolean force) throws IOException {
        boolean isNew = !configFile().exists();
        Map<String, Object> root = readConfig();
        Map<String, Object> s = child(child(root, "CVars"), "gSettings");
        boolean changed = isNew;
        for (Map.Entry<String, Integer> e : values.entrySet()) {
            if (force || !s.containsKey(e.getKey())) {
                s.put(e.getKey(), new Num(e.getValue()));
                changed = true;
            }
        }
        if (changed) writeConfig(root);
        return changed;
    }

    static void setGameSetting(String key, int value) throws IOException {
        Map<String, Object> root = readConfig();
        child(child(root, "CVars"), "gSettings").put(key, new Num(value));
        writeConfig(root);
    }

    @SuppressWarnings("unchecked")
    static int getGameSetting(String key, int fallback) {
        try {
            Object cvars = readConfig().get("CVars");
            if (!(cvars instanceof Map)) return fallback;
            Object settings = ((Map<String, Object>) cvars).get("gSettings");
            if (!(settings instanceof Map)) return fallback;
            Object v = ((Map<String, Object>) settings).get(key);
            return v instanceof Num ? ((Num) v).intValue() : fallback;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isLowRamDevice(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem > 0 && mi.totalMem <= LOW_RAM_BYTES;
    }

    /** What MainActivity renders at on a TV when no preset was chosen. */
    static int defaultTvRenderHeight(Context ctx) {
        return isLowRamDevice(ctx) ? 720 : 1080;
    }

    // ---- Literal-preserving JSON tree ----------------------------------------------------------

    /** A JSON number kept as its original text, so 1.0 stays 1.0. */
    static final class Num extends Number {
        final String literal;
        Num(String literal) { this.literal = literal; }
        Num(int v) { this.literal = Integer.toString(v); }
        @Override public int intValue() { return (int) doubleValue(); }
        @Override public long longValue() { return (long) doubleValue(); }
        @Override public float floatValue() { return (float) doubleValue(); }
        @Override public double doubleValue() {
            try { return Double.parseDouble(literal); } catch (NumberFormatException e) { return 0; }
        }
        @Override public String toString() { return literal; }
    }

    private static final Object NULL = new Object();

    static Map<String, Object> readConfig() throws IOException {
        File f = configFile();
        if (!f.exists()) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("ConfigVersion", new Num(3)); // current SoH config version: skip legacy migrations
            return root;
        }
        try (JsonReader r = new JsonReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            r.setLenient(true);
            Object v = readValue(r);
            if (!(v instanceof Map)) throw new IOException("Settings file isn't a JSON object");
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
            return m;
        } catch (IllegalStateException | NumberFormatException e) {
            throw new IOException("Couldn't read " + f.getName() + ": " + e.getMessage());
        }
    }

    private static Object readValue(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        switch (t) {
            case BEGIN_OBJECT: {
                Map<String, Object> m = new LinkedHashMap<>();
                r.beginObject();
                while (r.hasNext()) m.put(r.nextName(), readValue(r));
                r.endObject();
                return m;
            }
            case BEGIN_ARRAY: {
                List<Object> l = new ArrayList<>();
                r.beginArray();
                while (r.hasNext()) l.add(readValue(r));
                r.endArray();
                return l;
            }
            case NUMBER: return new Num(r.nextString()); // nextString keeps the literal text
            case STRING: return r.nextString();
            case BOOLEAN: return r.nextBoolean();
            case NULL: r.nextNull(); return NULL;
            default: r.skipValue(); return NULL;
        }
    }

    static void writeConfig(Map<String, Object> root) throws IOException {
        File f = configFile();
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (JsonWriter w = new JsonWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.setIndent("    ");
            writeValue(w, root);
        }
        // Rename over the old file (atomic on the same volume); fall back to delete+rename.
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Couldn't save " + f);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(JsonWriter w, Object v) throws IOException {
        if (v instanceof Map) {
            w.beginObject();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                w.name(e.getKey());
                writeValue(w, e.getValue());
            }
            w.endObject();
        } else if (v instanceof List) {
            w.beginArray();
            for (Object o : (List<Object>) v) writeValue(w, o);
            w.endArray();
        } else if (v instanceof Num) {
            w.value((Num) v); // JsonWriter writes Number.toString(): the original literal
        } else if (v instanceof String) {
            w.value((String) v);
        } else if (v instanceof Boolean) {
            w.value((Boolean) v);
        } else {
            w.nullValue();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object c = parent.get(key);
        if (c instanceof Map) return (Map<String, Object>) c;
        Map<String, Object> m = new LinkedHashMap<>();
        parent.put(key, m);
        return m;
    }

    static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private SohConfig() { }
}
