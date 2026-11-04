package com.dishii.soh;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pre-game launcher for TVs (and phones): Play, video presets, a GameBanana mod browser and an
 * installed-mods manager. Everything is navigable with the Fire TV remote or a gamepad.
 * Front ends that start MainActivity directly skip this screen.
 */
public class LauncherActivity extends Activity {

    // Palette (matches the flame icon)
    private static final int BG = 0xFF120D12;
    private static final int PANEL = 0xFF1E1519;
    private static final int CARD = 0xFF2A1D22;
    private static final int CARD_FOCUS = 0xFF4A2A22;
    private static final int ACCENT = 0xFFFF7A2E;
    private static final int GOLD = 0xFFFFC45C;
    private static final int TEXT = 0xFFF5EEE6;
    private static final int MUTED = 0xFFB9A9A4;
    private static final int OK_GREEN = 0xFF7BD88F;
    private static final int WARN = 0xFFFF6B5B;

    private static final int STORAGE_REQUEST = 41;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private FrameLayout content;
    private final List<TextView> navButtons = new ArrayList<>();
    private String currentPane = "play";

    // Browse state
    private int page = 1;
    private GameBananaApi.Sort sort = GameBananaApi.Sort.MOST_DOWNLOADED;
    private GameBananaApi.Category category = null;
    private String query = null;
    private List<GameBananaApi.Category> categories = null;
    private int browseRequest = 0;
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private boolean storageDeniedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildShell();
        showPane("play");
        if (!hasStorage()) requestStorage();
        else io.execute(() -> ModInstaller.cleanupStaging(getCacheDir()));
    }

    @Override
    protected void onDestroy() {
        for (AlertDialog d : dialogs) if (d.isShowing()) d.dismiss();
        dialogs.clear();
        io.shutdownNow();
        ImageLoader.reset();
        super.onDestroy();
    }

    /** Background callbacks must not touch a closed launcher (the game shares this process). */
    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private void ui(Runnable r) {
        runOnUiThread(() -> { if (alive()) r.run(); });
    }

    private AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.create();
        if (!alive()) return d;
        dialogs.add(d);
        d.setOnDismissListener(x -> dialogs.remove(d));
        d.show();
        return d;
    }

    // =============================================================================================
    // Shell: sidebar + content
    // =============================================================================================

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(PANEL);
        side.setPadding(dp(20), dp(24), dp(20), dp(20));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        side.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = text("Ship of\nHarkinian", 22, TEXT, true);
        title.setPadding(0, dp(8), 0, 0);
        side.addView(title);
        TextView sub = text("FIRE TV EDITION", 11, ACCENT, true);
        sub.setPadding(0, dp(2), 0, dp(20));
        side.addView(sub);

        addNav(side, "▶  Play", "play");
        addNav(side, "Video", "video");
        addNav(side, "Browse mods", "browse");
        addNav(side, "Installed mods", "installed");

        View spacer = new View(this);
        side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        TextView credit = text("Mods from GameBanana.com", 11, MUTED, false);
        side.addView(credit);

        root.addView(side, new LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        content.setPadding(dp(28), dp(24), dp(28), dp(16));
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);
    }

    private void addNav(LinearLayout side, String label, String pane) {
        TextView b = button(label, 17);
        b.setTag(pane);
        b.setOnClickListener(v -> showPane(pane));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.bottomMargin = dp(8);
        side.addView(b, lp);
        navButtons.add(b);
    }

    private void showPane(String pane) {
        currentPane = pane;
        for (TextView b : navButtons) {
            boolean sel = pane.equals(b.getTag());
            b.setTextColor(sel ? GOLD : TEXT);
            b.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
        content.removeAllViews();
        switch (pane) {
            case "video": buildVideoPane(); break;
            case "browse": buildBrowsePane(); break;
            case "installed": buildInstalledPane(); break;
            default: buildPlayPane(); break;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Back from a content pane returns to Play; from Play it exits.
        if (keyCode == KeyEvent.KEYCODE_BACK && !"play".equals(currentPane)) {
            showPane("play");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // =============================================================================================
    // Play
    // =============================================================================================

    private void buildPlayPane() {
        LinearLayout col = column();
        ImageView banner = new ImageView(this);
        banner.setImageResource(R.drawable.tv_banner);
        banner.setScaleType(ImageView.ScaleType.FIT_START);
        col.addView(banner, new LinearLayout.LayoutParams(dp(480), dp(270)));

        boolean running = MainActivity.sGameRunning;
        TextView play = button(running ? "▶   Resume game" : "▶   Play Ocarina of Time", 24);
        play.setTextColor(GOLD);
        play.setOnClickListener(v -> startGame());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(480), dp(72));
        lp.topMargin = dp(16);
        col.addView(play, lp);

        TextView info = text("Video: " + SohConfig.presetLabel(this) + "\nMods: …", 16, MUTED, false);
        info.setPadding(0, dp(16), 0, 0);
        col.addView(info);
        if (hasStorage()) {
            io.execute(() -> {
                int enabled = 0, total = 0;
                for (ModInstaller.InstalledMod m : ModInstaller.listInstalled()) { total++; if (m.enabled) enabled++; }
                final int e = enabled, t = total;
                ui(() -> info.setText("Video: " + SohConfig.presetLabel(this) + "\nMods: " + e + " enabled"
                        + (t > e ? " (" + (t - e) + " disabled)" : "")));
            });
        }
        if (running) {
            TextView warn = text("The game is still running. Video and mod changes apply after you quit it "
                    + "(Enhancements menu → power button) and start again.", 14, GOLD, false);
            warn.setPadding(0, dp(10), 0, 0);
            col.addView(warn);
        }

        TextView tip = text("In game: hold Menu ≡ (or press Select on a gamepad) for the Enhancements menu.", 14, MUTED, false);
        tip.setPadding(0, dp(10), 0, 0);
        col.addView(tip);

        content.addView(scroll(col));
        play.requestFocus();
    }

    private void startGame() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish(); // free the launcher's memory for the game
    }

    // =============================================================================================
    // Video presets
    // =============================================================================================

    private void buildVideoPane() {
        LinearLayout col = column();
        col.addView(header("Video presets", "Applied the next time the game starts."));

        String current = SohConfig.prefs(this).getString(SohConfig.PREF_VIDEO_PRESET, "");
        View first = null;
        for (SohConfig.Preset p : SohConfig.PRESETS) {
            boolean sel = p.id.equals(current);
            LinearLayout card = card();
            card.addView(text((sel ? "✓  " : "") + p.title, 19, sel ? GOLD : TEXT, true));
            card.addView(text(p.summary, 14, MUTED, false));
            card.setOnClickListener(v -> {
                if (!ensureStorage()) return;
                try {
                    SohConfig.applyPreset(this, p);
                    toast(p.title + " preset applied");
                    showPane("video");
                } catch (Exception e) {
                    error("Couldn't save the preset", e);
                }
            });
            col.addView(card, cardParams());
            if (first == null || sel) first = card;
        }

        col.addView(header("Fine-tune", null));
        int height = SohConfig.prefs(this).getInt(SohConfig.PREF_RENDER_HEIGHT, SohConfig.defaultTvRenderHeight(this));
        col.addView(cycleRow("Render resolution", new String[] { "480p", "720p", "1080p", "Native" },
                new int[] { 480, 720, 1080, 0 }, height, v -> {
                    SohConfig.prefs(this).edit().putInt(SohConfig.PREF_RENDER_HEIGHT, v)
                            .putString(SohConfig.PREF_VIDEO_PRESET, "custom").apply();
                }), cardParams());
        col.addView(settingRow("Frame rate", "InterpolationFPS",
                new String[] { "20 fps (original)", "30 fps", "60 fps" }, new int[] { 20, 30, 60 }, 20), cardParams());
        col.addView(settingRow("Texture filtering", "TextureFilter",
                new String[] { "N64 3-point", "Smooth (fastest)", "Sharp" }, new int[] { 0, 1, 2 }, 1), cardParams());
        col.addView(settingRow("N64 240p mode", "LowResMode",
                new String[] { "Off", "On" }, new int[] { 0, 1 }, 0), cardParams());

        content.addView(scroll(col));
        if (first != null) first.requestFocus();
    }

    private interface IntSetter { void set(int value) throws Exception; }

    private View settingRow(String label, String cvar, String[] names, int[] values, int fallback) {
        int cur = SohConfig.getGameSetting(cvar, fallback);
        return cycleRow(label, names, values, cur, v -> {
            SohConfig.setGameSetting(cvar, v);
            SohConfig.prefs(this).edit().putString(SohConfig.PREF_VIDEO_PRESET, "custom").apply();
        });
    }

    /** A row that cycles through values each time it's selected. */
    private View cycleRow(String label, String[] names, int[] values, int current, IntSetter setter) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 17, TEXT, false);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text("", 17, GOLD, true);
        row.addView(v);
        final int[] idx = { 0 };
        for (int i = 0; i < values.length; i++) if (values[i] == current) idx[0] = i;
        v.setText("◀  " + names[idx[0]] + "  ▶");
        row.setOnClickListener(x -> step(row, v, names, values, idx, setter, 1));
        row.setOnKeyListener((x, keyCode, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { step(row, v, names, values, idx, setter, 1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { step(row, v, names, values, idx, setter, -1); return true; }
            return false;
        });
        return row;
    }

    private void step(View row, TextView v, String[] names, int[] values, int[] idx, IntSetter setter, int dir) {
        if (!ensureStorage()) return;
        idx[0] = (idx[0] + dir + values.length) % values.length;
        v.setText("◀  " + names[idx[0]] + "  ▶");
        try {
            setter.set(values[idx[0]]);
        } catch (Exception e) {
            error("Couldn't save the setting", e);
        }
    }

    // =============================================================================================
    // Browse GameBanana
    // =============================================================================================

    private LinearLayout browseList;
    private TextView browseStatus, pageLabel, sortButton, categoryButton, searchButton;
    private TextView prevButton, nextButton;

    private void buildBrowsePane() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(header("Browse GameBanana mods", "for Ship of Harkinian"));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        searchButton = button(query == null ? "Search…" : "Search: " + query, 15);
        searchButton.setOnClickListener(v -> askSearch());
        sortButton = button("Sort: " + sort.label, 15);
        sortButton.setOnClickListener(v -> {
            GameBananaApi.Sort[] all = GameBananaApi.Sort.values();
            sort = all[(sort.ordinal() + 1) % all.length];
            sortButton.setText("Sort: " + sort.label);
            query = null; searchButton.setText("Search…");
            page = 1; loadBrowse();
        });
        categoryButton = button("Category: " + (category == null ? "All" : category.name), 15);
        categoryButton.setOnClickListener(v -> pickCategory());
        bar.addView(searchButton, toolbarParams());
        bar.addView(sortButton, toolbarParams());
        bar.addView(categoryButton, toolbarParams());
        col.addView(bar);

        browseStatus = text("", 15, MUTED, false);
        browseStatus.setPadding(0, dp(8), 0, dp(4));
        col.addView(browseStatus);

        browseList = new LinearLayout(this);
        browseList.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = scroll(browseList);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout pager = new LinearLayout(this);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setPadding(0, dp(8), 0, 0);
        prevButton = button("◀  Previous", 15);
        prevButton.setOnClickListener(v -> { if (page > 1) { page--; loadBrowse(); } });
        nextButton = button("Next  ▶", 15);
        nextButton.setOnClickListener(v -> { page++; loadBrowse(); });
        pageLabel = text("", 15, MUTED, false);
        pageLabel.setGravity(Gravity.CENTER);
        pager.addView(prevButton, new LinearLayout.LayoutParams(dp(150), dp(44)));
        pager.addView(pageLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pager.addView(nextButton, new LinearLayout.LayoutParams(dp(150), dp(44)));
        col.addView(pager);

        content.addView(col);
        sortButton.requestFocus();
        loadBrowse();
    }

    private void loadBrowse() {
        final int request = ++browseRequest;
        browseStatus.setText("Loading…");
        browseList.removeAllViews();
        final int p = page;
        final String q = query;
        final GameBananaApi.Sort s = sort;
        final int cat = category == null ? 0 : category.id;
        io.execute(() -> {
            try {
                GameBananaApi.Page result = q != null ? GameBananaApi.search(q, p) : GameBananaApi.browse(p, s, cat);
                Set<Integer> installed = hasStorage() ? ModInstaller.installedIds() : new java.util.HashSet<>();
                ui(() -> { if (request == browseRequest && "browse".equals(currentPane)) showPage(result, installed); });
            } catch (Exception e) {
                ui(() -> {
                    if (request != browseRequest) return;
                    browseStatus.setText("Couldn't reach GameBanana: " + e.getMessage()
                            + "\nCheck the TV's internet connection, then try again.");
                    TextView retry = button("Try again", 16);
                    retry.setOnClickListener(v -> loadBrowse());
                    browseList.addView(retry, new LinearLayout.LayoutParams(dp(200), dp(48)));
                    retry.requestFocus();
                });
            }
        });
    }

    private void showPage(GameBananaApi.Page result, Set<Integer> installed) {
        browseList.removeAllViews();
        String what = query != null ? "Results for \"" + query + "\"" : (category == null ? "All mods" : category.name);
        browseStatus.setText(what + "  ·  " + result.total + " mods");
        pageLabel.setText("Page " + page + " of " + result.pageCount());
        prevButton.setEnabled(page > 1);
        prevButton.setAlpha(page > 1 ? 1f : 0.4f);
        boolean more = page < result.pageCount();
        nextButton.setEnabled(more);
        nextButton.setAlpha(more ? 1f : 0.4f);
        if (result.mods.isEmpty()) {
            browseList.addView(text("No mods found.", 17, MUTED, false));
            return;
        }
        View firstCard = null;
        for (GameBananaApi.ModSummary m : result.mods) {
            View card = modCard(m, installed.contains(m.id));
            browseList.addView(card, cardParams());
            if (firstCard == null) firstCard = card;
        }
        // Only pull focus into the list if the user is still in the content area (not the sidebar).
        View focused = getCurrentFocus();
        if (focused == null || isInside(focused, content)) firstCard.requestFocus();
    }

    private static boolean isInside(View v, ViewGroup parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private View modCard(GameBananaApi.ModSummary m, boolean isInstalled) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundColor(0xFF000000);
        ImageLoader.load(thumb, m.thumbUrl, 220);
        card.addView(thumb, new LinearLayout.LayoutParams(dp(176), dp(99)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, 0, 0);
        TextView name = text(m.name, 18, TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(text("by " + m.author + (m.category.isEmpty() ? "" : "  ·  " + m.category), 14, MUTED, false));
        String stats = "♥ " + m.likes + "   👁 " + compact(m.views) + (m.version.isEmpty() ? "" : "   v" + m.version);
        texts.addView(text(stats, 14, MUTED, false));
        if (isInstalled) texts.addView(text("✓ Installed", 14, OK_GREEN, true));
        if (m.obsolete) texts.addView(text("Marked obsolete by its author", 13, WARN, false));
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnClickListener(v -> openMod(m));
        return card;
    }

    private void askSearch() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setHint("e.g. 3DS, Link, HD textures");
        if (query != null) input.setText(query);
        AlertDialog d = show(new AlertDialog.Builder(this)
                .setTitle("Search GameBanana")
                .setView(input)
                .setPositiveButton("Search", (x, w) -> runSearch(input.getText().toString()))
                .setNeutralButton("Clear", (x, w) -> runSearch(""))
                .setNegativeButton("Cancel", null));
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                d.dismiss();
                runSearch(input.getText().toString());
                return true;
            }
            return false;
        });
        input.requestFocus();
    }

    private void runSearch(String text) {
        String t = text.trim();
        query = t.isEmpty() ? null : t;
        searchButton.setText(query == null ? "Search…" : "Search: " + query);
        page = 1;
        loadBrowse();
    }

    private void pickCategory() {
        if (categories == null) {
            browseStatus.setText("Loading categories…");
            io.execute(() -> {
                try {
                    List<GameBananaApi.Category> list = GameBananaApi.categories();
                    ui(() -> { categories = list; if ("browse".equals(currentPane)) pickCategory(); });
                } catch (Exception e) {
                    ui(() -> error("Couldn't load categories", e));
                }
            });
            return;
        }
        String[] names = new String[categories.size() + 1];
        names[0] = "All categories";
        for (int i = 0; i < categories.size(); i++) {
            GameBananaApi.Category c = categories.get(i);
            names[i + 1] = c.name + (c.count > 0 ? "  (" + c.count + ")" : "");
        }
        show(new AlertDialog.Builder(this)
                .setTitle("Category")
                .setItems(names, (d, which) -> {
                    category = which == 0 ? null : categories.get(which - 1);
                    categoryButton.setText("Category: " + (category == null ? "All" : category.name));
                    query = null; searchButton.setText("Search…");
                    page = 1;
                    loadBrowse();
                }));
    }

    // ---- Mod details + install ----

    private void openMod(GameBananaApi.ModSummary m) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog loading = show(new AlertDialog.Builder(this).setMessage("Loading " + m.name + "…")
                .setOnCancelListener(x -> cancelled.set(true)));
        io.execute(() -> {
            try {
                GameBananaApi.ModDetails d = GameBananaApi.details(m.id);
                ui(() -> { loading.dismiss(); if (!cancelled.get()) showModDialog(m, d); });
            } catch (Exception e) {
                ui(() -> { loading.dismiss(); if (!cancelled.get()) error("Couldn't load this mod", e); });
            }
        });
    }

    private void showModDialog(GameBananaApi.ModSummary m, GameBananaApi.ModDetails d) {
        LinearLayout col = column();
        col.setPadding(dp(20), dp(16), dp(20), dp(8));
        col.setBackgroundColor(PANEL);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setBackgroundColor(0xFF000000);
        ImageLoader.load(img, d.imageUrl != null ? d.imageUrl : m.thumbUrl, 530);
        col.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        TextView title = text(m.name, 22, TEXT, true);
        title.setPadding(0, dp(10), 0, 0);
        col.addView(title);
        col.addView(text("by " + m.author + "  ·  " + m.category
                + (d.version.isEmpty() ? "" : "  ·  v" + d.version)
                + "  ·  ⬇ " + compact(d.downloads) + "  ♥ " + d.likes, 14, MUTED, false));
        if (m.dateUpdated > 0) {
            col.addView(text("Updated " + DateFormat.getDateInstance(DateFormat.MEDIUM)
                    .format(new Date(m.dateUpdated * 1000L)), 13, MUTED, false));
        }
        if (!d.text.isEmpty()) {
            TextView desc = text(d.text, 14, TEXT, false);
            desc.setPadding(0, dp(10), 0, dp(6));
            col.addView(desc);
        }

        col.addView(header("Files", d.files.isEmpty() ? "This mod has no downloadable files." : null));
        AlertDialog[] dialog = new AlertDialog[1];
        View firstFile = null;
        for (GameBananaApi.ModFile f : d.files) {
            LinearLayout row = card();
            String lower = f.name.toLowerCase(Locale.ROOT);
            boolean supported = lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar")
                    || ModInstaller.isModArchive(lower);
            row.addView(text("⬇  " + f.name, 16, supported ? TEXT : MUTED, true));
            String meta = ModInstaller.formatSize(f.size) + "  ·  " + f.downloads + " downloads";
            if (!f.looksSafe()) meta += "  ·  ⚠ flagged by GameBanana's scan";
            else if (!supported) meta += "  ·  file type not supported here";
            row.addView(text(meta, 13, f.looksSafe() ? MUTED : WARN, false));
            if (supported && f.looksSafe()) {
                row.setOnClickListener(v -> { if (dialog[0] != null) dialog[0].dismiss(); install(m, f); });
                if (firstFile == null) firstFile = row;
            } else {
                row.setAlpha(0.6f);
            }
            col.addView(row, cardParams());
        }
        TextView open = text("More info: " + m.profileUrl, 12, MUTED, false);
        open.setPadding(0, dp(8), 0, 0);
        col.addView(open);

        dialog[0] = show(new AlertDialog.Builder(this)
                .setView(scroll(col))
                .setNegativeButton("Close", null));
        if (firstFile != null) firstFile.requestFocus();
    }

    private void install(GameBananaApi.ModSummary m, GameBananaApi.ModFile f) {
        if (!ensureStorage()) return;
        if (!ModInstaller.tryBegin()) {
            toast("Another mod is still installing.");
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        LinearLayout box = column();
        box.setPadding(dp(24), dp(20), dp(24), dp(12));
        TextView stage = text("Starting download…", 16, TEXT, false);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(stage);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        AlertDialog progress = show(new AlertDialog.Builder(this)
                .setTitle("Installing " + m.name)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancel.set(true)));
        io.execute(() -> {
            boolean handedOff = false;
            try {
                ModInstaller.Staged staged = ModInstaller.prepare(m, f, getCacheDir(), (s, done, total) -> ui(() -> {
                    if (total > 0) {
                        bar.setIndeterminate(false);
                        bar.setProgress((int) (done * 1000 / total));
                        stage.setText(s + "  " + ModInstaller.formatSize(done) + " / " + ModInstaller.formatSize(total));
                    } else {
                        bar.setIndeterminate(true);
                        stage.setText(s);
                    }
                }), cancel);
                if (cancel.get() || !alive()) {
                    ModInstaller.discard(staged);
                    return;
                }
                handedOff = true;
                ui(() -> {
                    progress.dismiss();
                    if (staged.files.size() == 1) commitInstall(staged, staged.files);
                    else chooseFiles(staged);
                });
            } catch (ModInstaller.CancelledException e) {
                ui(() -> toast("Install cancelled"));
            } catch (Throwable e) { // includes library Errors, which must not take the game down
                ui(() -> { progress.dismiss(); error("Couldn't install " + m.name, e); });
            } finally {
                if (!handedOff) {
                    ModInstaller.end();
                    ui(progress::dismiss);
                }
            }
        });
    }

    /** "Pick a variant" packs: let the user choose which mod files to keep (all are selected). */
    private void chooseFiles(ModInstaller.Staged staged) {
        String[] labels = new String[staged.files.size()];
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            ModInstaller.StagedFile sf = staged.files.get(i);
            labels[i] = sf.file.getName() + "\n" + sf.label;
            checked[i] = true;
        }
        final boolean[] done = { false };
        AlertDialog d = show(new AlertDialog.Builder(this)
                .setTitle("This download has " + labels.length + " mod files")
                .setMultiChoiceItems(labels, checked, (x, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Install selected", (x, w) -> {
                    done[0] = true;
                    List<ModInstaller.StagedFile> keep = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) keep.add(staged.files.get(i));
                    if (keep.isEmpty()) {
                        ModInstaller.discard(staged);
                        ModInstaller.end();
                        toast("Nothing selected; install cancelled");
                    } else {
                        commitInstall(staged, keep);
                    }
                })
                .setNegativeButton("Cancel", (x, w) -> { }));
        d.setOnDismissListener(x -> {
            dialogs.remove(d);
            if (!done[0]) {
                ModInstaller.discard(staged);
                ModInstaller.end();
            }
        });
    }

    private void commitInstall(ModInstaller.Staged staged, List<ModInstaller.StagedFile> keep) {
        io.execute(() -> {
            try {
                File where = ModInstaller.commit(staged, keep);
                int n = keep.size();
                ui(() -> show(new AlertDialog.Builder(this)
                        .setTitle("Installed")
                        .setMessage(staged.mod.name + " is installed (" + n + " mod file" + (n == 1 ? "" : "s")
                                + ", in mods/" + where.getName() + "). It loads the next time the game starts.")
                        .setPositiveButton("OK", (x, w) -> { if ("browse".equals(currentPane)) loadBrowse(); })));
            } catch (Throwable e) {
                ui(() -> error("Couldn't install " + staged.mod.name, e));
            } finally {
                ModInstaller.end();
            }
        });
    }

    // =============================================================================================
    // Installed mods
    // =============================================================================================

    private void buildInstalledPane() {
        LinearLayout col = column();
        col.addView(header("Installed mods",
                "Mods load in alphabetical order; later ones override earlier ones. Changes apply the next time the game starts."));
        if (!hasStorage()) {
            col.addView(text("Allow storage access to manage mods.", 16, WARN, false));
            TextView grant = button("Allow storage access", 16);
            grant.setOnClickListener(v -> requestStorage());
            col.addView(grant, new LinearLayout.LayoutParams(dp(280), dp(48)));
            content.addView(scroll(col));
            grant.requestFocus();
            return;
        }
        List<ModInstaller.InstalledMod> mods = ModInstaller.listInstalled();
        if (mods.isEmpty()) {
            col.addView(text("No mods installed yet. Find some under Browse mods, or copy .otr/.o2r files "
                    + "into the SOH/mods folder.", 16, MUTED, false));
            TextView go = button("Browse mods", 16);
            go.setOnClickListener(v -> showPane("browse"));
            col.addView(go, new LinearLayout.LayoutParams(dp(220), dp(48)));
            content.addView(scroll(col));
            go.requestFocus();
            return;
        }
        View first = null;
        for (ModInstaller.InstalledMod m : mods) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackgroundColor(CARD);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(m.name, 17, m.enabled ? TEXT : MUTED, true));
            texts.addView(text(ModInstaller.formatSize(m.sizeBytes)
                    + (m.version.isEmpty() ? "" : "  ·  v" + m.version)
                    + (m.gameBananaId != 0 ? "  ·  GameBanana" : "  ·  added by hand"), 13, MUTED, false));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView toggle = button(m.enabled ? "✓ Enabled" : "Disabled", 15);
            toggle.setTextColor(m.enabled ? OK_GREEN : MUTED);
            toggle.setOnClickListener(v -> {
                try {
                    ModInstaller.setEnabled(m, !m.enabled);
                    showPane("installed");
                } catch (Exception e) {
                    error("Couldn't change " + m.name, e);
                }
            });
            row.addView(toggle, new LinearLayout.LayoutParams(dp(140), dp(44)));

            TextView remove = button("Remove", 15);
            remove.setOnClickListener(v -> show(new AlertDialog.Builder(this)
                    .setTitle("Remove " + m.name + "?")
                    .setMessage("This deletes the mod's files from the TV.")
                    .setPositiveButton("Remove", (d, w) -> {
                        try { ModInstaller.delete(m); showPane("installed"); }
                        catch (Exception e) { error("Couldn't remove " + m.name, e); }
                    })
                    .setNegativeButton("Cancel", null)));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(110), dp(44));
            rp.leftMargin = dp(8);
            row.addView(remove, rp);

            col.addView(row, cardParams());
            if (first == null) first = toggle;
        }
        content.addView(scroll(col));
        if (first != null) first.requestFocus();
    }

    // =============================================================================================
    // Storage permission (same model as MainActivity: legacy storage on Fire OS 7/8)
    // =============================================================================================

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) return true;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensureStorage() {
        if (hasStorage()) {
            ModInstaller.modsDir().mkdirs();
            return true;
        }
        requestStorage();
        return false;
    }

    private void requestStorage() {
        if (storageDeniedOnce && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // "Don't ask again" was chosen: the prompt won't appear, so open the app's settings page.
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                toast("Turn on Storage under Permissions, then come back.");
            } catch (Exception e) {
                toast("Allow storage for this app in Settings → Applications.");
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, STORAGE_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_REQUEST) {
            if (!hasStorage()) {
                storageDeniedOnce = true;
                toast("Storage access is needed for mods and settings.");
            }
            showPane(currentPane);
        }
    }

    // =============================================================================================
    // Small UI helpers
    // =============================================================================================

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    /** Focusable button with a visible focus state for the remote. */
    private TextView button(String label, int sp) {
        TextView b = text(label, sp, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setBackground(focusBackground(CARD));
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.setBackground(focusBackground(CARD));
        return c;
    }

    private Drawable focusBackground(int normal) {
        GradientDrawable n = new GradientDrawable();
        n.setColor(normal);
        n.setCornerRadius(dp(8));
        GradientDrawable f = new GradientDrawable();
        f.setColor(CARD_FOCUS);
        f.setCornerRadius(dp(8));
        f.setStroke(dp(3), ACCENT);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_focused }, f);
        s.addState(new int[] { android.R.attr.state_pressed }, f);
        s.addState(new int[] {}, n);
        return s;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(child);
        return s;
    }

    private View header(String title, String subtitle) {
        LinearLayout h = column();
        h.setPadding(0, dp(8), 0, dp(10));
        h.addView(text(title, 24, TEXT, true));
        if (subtitle != null) h.addView(text(subtitle, 14, ACCENT, false));
        return h;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return lp;
    }

    private LinearLayout.LayoutParams toolbarParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.rightMargin = dp(8);
        return lp;
    }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void error(String title, Throwable e) {
        if (!alive()) return;
        show(new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(e.getMessage() != null ? e.getMessage() : e.toString())
                .setPositiveButton("OK", null));
    }
}
