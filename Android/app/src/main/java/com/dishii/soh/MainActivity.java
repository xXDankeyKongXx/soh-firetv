
package com.dishii.soh;
import org.libsdl.app.SDLActivity;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

import android.util.Log;

import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import android.app.AlertDialog;

//This class is the main SDLActivity and just sets up a bunch of default files
public class MainActivity extends SDLActivity{

    private static final String TAG = "SoH";

    SharedPreferences preferences;
    private static final CountDownLatch setupLatch = new CountDownLatch(1);

    // ---- Fire TV / front-end launch support ----
    // Intent extras a front end can pass (all optional):
    //   rom            String   absolute path to an N64 OoT rom (.z64/.n64/.v64), used when the game asks for a rom
    //   resolution     int      render height: 480, 720, 1080, or 0 for native. Saved for later launches.
    //   touchControls  boolean  force the on-screen touch controls on/off. Saved for later launches.
    //   remoteControls boolean  play with the TV remote (default on for TVs). Saved for later launches.
    //   applyTvPreset  boolean  re-apply the TV graphics preset to an existing config.
    //   menu           String   "open", "close" or "toggle" the Enhancements menu (also works on a running game).
    // The activity's data URI (file:// or content://) is also accepted as the rom.
    public static final String EXTRA_ROM = "rom";
    public static final String EXTRA_RESOLUTION = "resolution";
    public static final String EXTRA_TOUCH_CONTROLS = "touchControls";
    public static final String EXTRA_REMOTE_CONTROLS = "remoteControls";
    public static final String EXTRA_APPLY_TV_PRESET = "applyTvPreset";
    public static final String EXTRA_MENU = "menu";

    // Enhancements menu requests (must match SohTv::MenuRequest in TvMenuBridge.h)
    static final int MENU_OPEN = 1;
    static final int MENU_CLOSE = 2;
    static final int MENU_TOGGLE = 3;
    static final int MENU_NEXT_TAB = 4;
    static final int MENU_PREV_TAB = 5;
    static final int MENU_NEXT_SECTION = 6;
    static final int MENU_PREV_SECTION = 7;

    // One background thread for rom scanning/copying instead of a new executor per use.
    private static final java.util.concurrent.ExecutorService BACKGROUND = Executors.newSingleThreadExecutor();
    private static final String[] ROM_EXTRA_ALIASES = { EXTRA_ROM, "ROM", "romPath", "ROM_PATH", "path" };
    private static final int TV_DEFAULT_RENDER_HEIGHT = 720;
    private static final int TV_HIGH_RAM_RENDER_HEIGHT = 1080;
    private static final long LOW_RAM_BYTES = 2560L * 1024 * 1024; // 2 GB-class TVs and sticks

    private boolean isTv;
    private String launcherRomPath;   // file path handed over by a front end
    private Uri launcherRomUri;       // content:// uri handed over by a front end
    private boolean romChooserShowing = false;
    private boolean forceTvPreset = false;
    private RemoteInput remote;
    /** True while the game activity exists; the launcher uses it to offer "Resume" instead of a fresh start. */
    static volatile boolean sGameRunning = false;
    private VoiceControl voice;
    private int pendingMenuRequest = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sGameRunning = true;

        preferences = getSharedPreferences("com.dishii.soh.prefs",Context.MODE_PRIVATE);
        isTv = detectTv();
        readLauncherIntent(getIntent());

        // Check if storage permissions are granted
        if (hasStoragePermission()) {
            doVersionCheck();
            checkAndSetupFiles();
        } else {
            requestStoragePermission();
        }

        super.onCreate(savedInstanceState);

        applyRenderResolution();

        if (useTouchControls()) {
            setupControllerOverlay();
            attachController();
        } else if (useRemoteControls()) {
            // No touchscreen: drive the same virtual controller from the TV remote instead.
            // A Bluetooth gamepad still works alongside it (both feed player 1).
            attachController();
            // Start both triggers released (-max), otherwise Z/R can read as half pressed.
            setButton(ControllerButtons.AXIS_RT, false);
            setButton(ControllerButtons.BUTTON_RB, false);
            remote = new RemoteInput(this);
            remote.attachIndicator((ViewGroup) getContentView());
            showRemoteHelpOnce();
        }

        // Alexa voice commands ("Alexa, pause" opens the Enhancements menu, etc.)
        if (isTv) {
            try {
                voice = new VoiceControl(this);
            } catch (RuntimeException e) {
                Log.w(TAG, "Voice control unavailable", e);
            }
        }

        if (pendingMenuRequest != 0) {
            requestMenu(pendingMenuRequest);
            pendingMenuRequest = 0;
        }

        // Ask the TV for its low-latency game picture mode (Android 11 / Fire OS 8+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setPreferMinimalPostProcessing(true);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (remote != null && remote.handle(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        if (remote != null) remote.releaseAll();
        if (voice != null) voice.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (voice != null) voice.onResume();
    }

    @Override
    protected void onDestroy() {
        sGameRunning = false;
        if (voice != null) voice.release();
        super.onDestroy();
    }

    // ---- Enhancements menu bridge (native side: soh/soh/SohGui/TvMenuBridge.cpp) ----
    private native boolean nativeIsMenuOpen();
    private native void nativeMenuRequest(int request);

    boolean isMenuOpen() {
        try {
            return nativeIsMenuOpen();
        } catch (UnsatisfiedLinkError e) {
            return false; // native library not loaded yet
        }
    }

    void requestMenu(int request) {
        try {
            nativeMenuRequest(request);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Menu request before the game loaded", e);
        }
    }

    private boolean useRemoteControls() {
        return preferences.getBoolean("remoteControlsEnabled", isTv);
    }

    private void showRemoteHelpOnce() {
        if (preferences.getBoolean("remoteHelpShown", false)) return;
        preferences.edit().putBoolean("remoteHelpShown", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Playing with the remote")
                .setMessage("D-pad: move\n"
                        + "Select: A        Back: B\n"
                        + "Play/Pause: Start\n"
                        + "Rewind: Z target     Fast-forward: R shield\n"
                        + "Menu tap, then D-pad: C-buttons\n"
                        + "Menu hold: Enhancements menu\n\n"
                        + "Voice: \"Alexa, pause\" opens the Enhancements menu, \"Alexa, play\" closes it, "
                        + "\"Alexa, next/previous\" switches tabs.\n\n"
                        + "A Bluetooth gamepad works too and is easier for combat.")
                .setPositiveButton("Got it", null)
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLauncherIntent(intent);
    }

    // ---------------------------------------------------------------------------------------------
    // Device detection / launcher options
    // ---------------------------------------------------------------------------------------------

    private boolean detectTv() {
        PackageManager pm = getPackageManager();
        UiModeManager ui = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        boolean tvMode = ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        return tvMode
                || pm.hasSystemFeature("amazon.hardware.fire_tv")
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private boolean useTouchControls() {
        return preferences.getBoolean("touchControlsEnabled", !isTv);
    }

    private void readLauncherIntent(Intent intent) {
        if (intent == null) return;

        for (String key : ROM_EXTRA_ALIASES) {
            String path = intent.getStringExtra(key);
            if (path != null && !path.isEmpty()) {
                launcherRomPath = path.startsWith("file://") ? Uri.parse(path).getPath() : path;
                break;
            }
        }
        Uri data = intent.getData();
        if (launcherRomPath == null && data != null) {
            if ("file".equals(data.getScheme())) {
                launcherRomPath = data.getPath();
            } else if ("content".equals(data.getScheme())) {
                launcherRomUri = data;
            }
        }

        SharedPreferences.Editor ed = preferences.edit();
        if (intent.hasExtra(EXTRA_RESOLUTION)) {
            ed.putInt("renderHeight", intent.getIntExtra(EXTRA_RESOLUTION, TV_DEFAULT_RENDER_HEIGHT));
        }
        if (intent.hasExtra(EXTRA_TOUCH_CONTROLS)) {
            ed.putBoolean("touchControlsEnabled", intent.getBooleanExtra(EXTRA_TOUCH_CONTROLS, !isTv));
        }
        if (intent.hasExtra(EXTRA_REMOTE_CONTROLS)) {
            ed.putBoolean("remoteControlsEnabled", intent.getBooleanExtra(EXTRA_REMOTE_CONTROLS, isTv));
        }
        forceTvPreset |= intent.getBooleanExtra(EXTRA_APPLY_TV_PRESET, false);

        String menu = intent.getStringExtra(EXTRA_MENU);
        if (menu != null) {
            int req = "open".equalsIgnoreCase(menu) ? MENU_OPEN
                    : "close".equalsIgnoreCase(menu) ? MENU_CLOSE
                    : "toggle".equalsIgnoreCase(menu) ? MENU_TOGGLE : 0;
            if (req != 0) {
                if (mSurface != null) requestMenu(req);   // game already running (onNewIntent)
                else pendingMenuRequest = req;            // first launch: send after the library loads
            }
        }
        ed.apply();
    }

    // Render into a smaller surface and let the display hardware upscale it for free.
    // Fire TV GPUs struggle at 1080p+, and the TV scaler does a good job with 720p.
    private void applyRenderResolution() {
        int targetHeight = preferences.getInt("renderHeight", isTv ? SohConfig.defaultTvRenderHeight(this) : 0);
        if (targetHeight <= 0 || mSurface == null) return;

        DisplayMetrics real = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(real);
        int screenW = Math.max(real.widthPixels, real.heightPixels);
        int screenH = Math.min(real.widthPixels, real.heightPixels);
        if (screenH <= 0 || targetHeight >= screenH) return;

        int targetWidth = Math.round(targetHeight * (screenW / (float) screenH));
        targetWidth &= ~1; // keep it even
        Log.i(TAG, "Render surface " + targetWidth + "x" + targetHeight + " (display " + screenW + "x" + screenH + ")");
        // SurfaceView measures itself from setFixedSize() when its layout is WRAP_CONTENT (SDL's
        // default), which shrank the game into the top-left corner. Fill the screen explicitly so the
        // smaller buffer is scaled up to full size by the display hardware.
        mSurface.setLayoutParams(new android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mSurface.getHolder().setFixedSize(targetWidth, targetHeight);
    }

    private long totalRamBytes() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem;
    }

    private boolean isLowRamDevice() {
        long ram = totalRamBytes();
        return ram > 0 && ram <= LOW_RAM_BYTES;
    }

    // 2 GB TVs and sticks (e.g. Insignia/Toshiba Fire TVs) get 720p; roomier devices get 1080p.
    private int defaultTvRenderHeight() {
        return isLowRamDevice() ? TV_DEFAULT_RENDER_HEIGHT : TV_HIGH_RAM_RENDER_HEIGHT;
    }

    // ---------------------------------------------------------------------------------------------
    // TV graphics preset
    //
    // Written into SOH/shipofharkinian.json before the game reads it. Only fills in settings the
    // user hasn't set, unless the "applyTvPreset" extra is passed. Keys are SoH 9.0.2 CVars.
    // ---------------------------------------------------------------------------------------------

    private void applyTvConfigPreset(File sohRoot) {
        if (!isTv) return;
        java.util.Map<String, Integer> values = new java.util.LinkedHashMap<>();
        // Hardware bilinear instead of the shader-based 3-point N64 filter: 1 texture fetch, not 3.
        values.put("TextureFilter", 1);
        // MSAA is expensive on Mali tilers and the cause of the known black-screen bug.
        values.put("MSAAValue", 1);
        // Original 20 fps logic rate; raise in the launcher's Video page if the TV keeps up.
        values.put("InterpolationFPS", 20);
        // Menu size index (0.75x, 1x, 1.5x, 2x). 1.5x reads well from a couch at 720p.
        values.put("ImGuiScale", 2);
        // Navigate the Enhancements menu with the remote/gamepad.
        values.put("ControlNav", 1);
        try {
            // Edits the file without reformatting numbers (see SohConfig); only fills in missing keys
            // unless the applyTvPreset extra was passed.
            if (SohConfig.applyIntSettings(values, forceTvPreset)) {
                Log.i(TAG, "Applied TV preset" + (forceTvPreset ? " (forced)" : ""));
            }
        } catch (IOException e) {
            Log.w(TAG, "Couldn't apply TV preset", e);
        }
        forceTvPreset = false;
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { // Files.readAllBytes needs API 26; minSdk is 24
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }



    public static void waitForSetupFromNative() {
        try {
            setupLatch.await();  // Block until setup is complete
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Bump when the bundled game (soh.otr / extractor assets) changes, e.g. a new SoH release.
    // Deliberately NOT the app's versionCode: Fire TV app updates happen often (in-app updater) and
    // must not delete oot.otr, which would force a long re-extraction from the ROM every time.
    private static final int GAME_ASSETS_VERSION = 902; // SoH 9.0.2

    private void doVersionCheck(){
        int stored = preferences.getInt("gameAssetsVersion", 0);
        if (stored == 0 && preferences.contains("appVersion")) {
            // Installed before this check existed, already on 9.0.2: keep the extracted files.
            stored = GAME_ASSETS_VERSION;
            preferences.edit().putInt("gameAssetsVersion", stored).apply();
        }
        if (stored != 0 && stored < GAME_ASSETS_VERSION) {
            deleteOutdatedAssets();
        }
        if (stored != GAME_ASSETS_VERSION) {
            preferences.edit().putInt("gameAssetsVersion", GAME_ASSETS_VERSION).apply();
        }
    }

    private void deleteOutdatedAssets() {
        File targetRootFolder = new File(Environment.getExternalStorageDirectory(), "SOH");

        File sohFile = new File(targetRootFolder, "soh.otr");
        File ootFile = new File(targetRootFolder, "oot.otr");
        File ootMqFile = new File(targetRootFolder, "oot-mq.otr");
        File assetsFolder = new File(targetRootFolder, "assets");

        deleteIfExists(sohFile);
        deleteIfExists(ootFile);
        deleteIfExists(ootMqFile);
        deleteRecursiveIfExists(assetsFolder);
    }

    private void deleteIfExists(File file) {
        if (file.exists()) {
            if (file.delete()) {
                Log.i("deleteAssets", "Deleted file: " + file.getAbsolutePath());
            } else {
                Log.w("deleteAssets", "Failed to delete file: " + file.getAbsolutePath());
            }
        } else {
            Log.i("deleteAssets", "File not found (skipped): " + file.getAbsolutePath());
        }
    }

    private void deleteRecursiveIfExists(File dir) {
        if (dir.exists()) {
            deleteRecursive(dir);
            Log.i("deleteAssets", "Deleted directory: " + dir.getAbsolutePath());
        } else {
            Log.i("deleteAssets", "Directory not found (skipped): " + dir.getAbsolutePath());
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    // ---------------------------------------------------------------------------------------------
    // Storage permission
    //
    // Fire OS 7 (Android 9) uses the normal runtime permission prompt. Fire OS 8 (Android 11) has no
    // "All files access" settings screen, so this build targets SDK 29 with
    // requestLegacyExternalStorage, which keeps the old READ/WRITE permission model working there.
    // ---------------------------------------------------------------------------------------------

    private boolean usesLegacyStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return Environment.isExternalStorageLegacy();
    }

    private boolean hasRuntimeStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    // Check if storage permission is granted
    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !usesLegacyStorage()) {
            return false; // scoped storage: only "All files access" works for /sdcard/SOH
        }
        return hasRuntimeStoragePermission();
    }

    private static final int STORAGE_PERMISSION_REQUEST_CODE = 2296;
    private static final int FILE_PICKER_REQUEST_CODE = 0;

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !usesLegacyStorage()) {
            requestAllFilesAccess();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    STORAGE_PERMISSION_REQUEST_CODE);
        } else {
            // Below Android 6 → permissions granted at install time
            checkAndSetupFiles();
        }
    }

    private void requestAllFilesAccess() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
            return;
        } catch (ActivityNotFoundException ignored) { }
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), STORAGE_PERMISSION_REQUEST_CODE);
            return;
        } catch (ActivityNotFoundException ignored) { }
        // Fire TV and some Android TV boxes have no screen for this permission at all.
        showStorageHelpDialog(true);
    }

    private void showStorageHelpDialog(boolean noSettingsScreen) {
        String msg = noSettingsScreen
                ? "This device has no settings screen for file access. Grant it once from a computer with:\n\n"
                  + "adb shell appops set " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow\n\n"
                  + "then press Retry."
                : "Storage access is needed to set up the game files in the SOH folder.";
        new AlertDialog.Builder(this)
                .setTitle("Storage access required")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Retry", (d, w) -> {
                    if (hasStoragePermission()) {
                        doVersionCheck();
                        checkAndSetupFiles();
                    } else {
                        requestStoragePermission();
                    }
                })
                .setNegativeButton("Quit", (d, w) -> quitApp())
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != STORAGE_PERMISSION_REQUEST_CODE) return;
        // The original build never continued after this prompt on Android 6-10 (Fire OS 7),
        // which left the game stuck on a black screen waiting for setup.
        if (hasStoragePermission()) {
            doVersionCheck();
            checkAndSetupFiles();
        } else {
            showStorageHelpDialog(false);
        }
    }

    private void quitApp() {
        finishAffinity();
        System.exit(0);
    }

    public void checkAndSetupFiles() {
        File targetRootFolder = new File(Environment.getExternalStorageDirectory(), "SOH");
        File assetsFolder = new File(targetRootFolder, "assets");
        File sohOtrFile = new File(targetRootFolder, "soh.otr");

        boolean isMissingAssets = !assetsFolder.exists() || assetsFolder.listFiles() == null || assetsFolder.listFiles().length == 0;
        boolean isMissingSohOtr = !sohOtrFile.exists();

        if (!targetRootFolder.exists() || isMissingAssets || isMissingSohOtr) {
            new AlertDialog.Builder(this)
                    .setTitle("Setup Required")
                    .setMessage("Some required files are missing. The app will create them (~1 minute). Press OK to begin.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> {
                        Executors.newSingleThreadExecutor().execute(() -> {
                            runOnUiThread(() -> Toast.makeText(this, "Setting up files...", Toast.LENGTH_SHORT).show());
                            setupFilesInBackground(targetRootFolder);
                        });
                    })
                    .show();
        } else {
            // No setup needed, still need to count down
            applyTvConfigPreset(targetRootFolder);
            setupLatch.countDown();
        }
    }


    private void setupFilesInBackground(File targetRootFolder) {

        File sourceOldRoot = getExternalFilesDir(null);
        File sourceSavesDir = new File(sourceOldRoot, "Save"); // how to tell if there's anything to migrate

        // === Migration from old Android/data/.../files/ directory ===
        if (sourceOldRoot != null && sourceSavesDir.isDirectory()) {
            Log.i("setupFiles", "Migrating old data from: " + sourceOldRoot.getAbsolutePath());

            File[] sourceFiles = sourceOldRoot.listFiles();
            if (sourceFiles != null) {
                for (File file : sourceFiles) {
                    String name = file.getName();
                    if (name.equals("assets") || name.equals("soh.otr") || name.equals("oot-mq.otr") || name.equals("oot.otr")) {
                        continue; // Skip these
                    }

                    File dest = new File(targetRootFolder, name);
                    try {
                        if (file.isDirectory()) {
                            AssetCopyUtil.copyDirectory(file, dest);
                        } else {
                            AssetCopyUtil.copyFile(file, dest);
                        }
                        Log.i("setupFiles", "Migrated: " + name);
                    } catch (IOException e) {
                        Log.e("setupFiles", "Failed to migrate: " + name, e);
                    }
                }
            }

            runOnUiThread(() -> Toast.makeText(this, "Save data migrated", Toast.LENGTH_SHORT).show());
        }

        // Ensure root folder exists
        if (!targetRootFolder.exists()) {
            if (!targetRootFolder.mkdirs()) {
                Log.e("setupFiles", "Failed to create root folder");
                runOnUiThread(() -> Toast.makeText(this, "Failed to create folder", Toast.LENGTH_LONG).show());
                setupLatch.countDown();
                return;
            }
        }

        // Always ensure mods folder exists
        File targetModsDir = new File(targetRootFolder, "mods");
        if (!targetModsDir.exists()) {
            targetModsDir.mkdirs();
        }

        // Copy assets/ from internal
        File targetAssetsDir = new File(targetRootFolder, "assets");
        try {
            if (!targetAssetsDir.exists()) {
                targetAssetsDir.mkdirs();
            }
            showStatus("Setting up game files…");
            AssetCopyUtil.copyAssetsToExternal(this, "assets", targetAssetsDir.getAbsolutePath(),
                    (copied, total) -> showStatus("Setting up game files…  " + copied + " / " + total));
            showStatus("Game files ready. Starting…");
        } catch (IOException e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error copying assets", Toast.LENGTH_LONG).show());
        }

        // Copy soh.otr from internal assets
        showStatus("Copying soh.otr…");
        File targetOtrFile = new File(targetRootFolder, "soh.otr");
        File partialOtrFile = new File(targetRootFolder, "soh.otr.part"); // renamed only when complete
        try (InputStream in = getAssets().open("soh.otr");
             OutputStream out = new FileOutputStream(partialOtrFile)) {

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.close();
            targetOtrFile.delete();
            if (!partialOtrFile.renameTo(targetOtrFile)) throw new IOException("rename soh.otr failed");

            runOnUiThread(() -> Toast.makeText(this, "soh.otr copied", Toast.LENGTH_SHORT).show());

        } catch (IOException e) {
            e.printStackTrace();
            runOnUiThread(() -> Toast.makeText(this, "Error copying soh.otr", Toast.LENGTH_LONG).show());
        }

        applyTvConfigPreset(targetRootFolder);
        hideStatusSoon();
        setupLatch.countDown();
    }

    // ---------------------------------------------------------------------------------------------
    // On-screen status (first-run setup can take a while on a TV; show that it's working)
    // ---------------------------------------------------------------------------------------------

    private android.widget.TextView statusView;

    private void showStatus(String text) {
        runOnUiThread(() -> {
            ViewGroup root = (ViewGroup) getContentView();
            if (root == null) return;
            if (statusView == null) {
                statusView = new android.widget.TextView(this);
                statusView.setTextColor(0xFFFFF4E4);
                statusView.setTextSize(24);
                statusView.setPadding(48, 28, 48, 28);
                statusView.setBackgroundColor(0xCC000000);
                android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT);
                root.addView(statusView, lp);
            }
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
            statusView.bringToFront();
        });
    }

    private void hideStatusSoon() {
        runOnUiThread(() -> {
            if (statusView != null) statusView.postDelayed(() -> statusView.setVisibility(View.GONE), 3000);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // ROM selection
    //
    // The native extractor calls openFilePicker() from the game thread and then waits until
    // nativeHandleSelectedFile() is called. Fire TV has no system file picker, so this shows a
    // remote/D-pad friendly list of roms found on the device instead, and never leaves the native
    // side waiting forever (the original hung if the picker was cancelled).
    // ---------------------------------------------------------------------------------------------

    private native void nativeHandleSelectedFile(String filePath);

    public void openFilePicker() {
        runOnUiThread(this::chooseRom);
    }

    private void chooseRom() {
        // 1. A front end told us which rom to use.
        if (launcherRomPath != null) {
            File f = new File(launcherRomPath);
            launcherRomPath = null; // only use it once, so "extract another rom?" shows the list
            if (f.isFile() && f.canRead()) {
                deliverRom(f);
                return;
            }
            Toast.makeText(this, "Can't read rom from launcher: " + f.getPath(), Toast.LENGTH_LONG).show();
        }
        if (launcherRomUri != null) {
            Uri uri = launcherRomUri;
            launcherRomUri = null;
            copyUriAndDeliver(uri);
            return;
        }
        // 2. Let the user pick from roms found on the device.
        showRomChooser();
    }

    private void showRomChooser() {
        if (romChooserShowing) return;
        romChooserShowing = true;

        BACKGROUND.execute(() -> {
            List<File> roms = scanForRoms();
            runOnUiThread(() -> buildRomChooser(roms));
        });
    }

    private void buildRomChooser(List<File> roms) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        File sdRoot = Environment.getExternalStorageDirectory();

        for (File rom : roms) {
            String shown = rom.getAbsolutePath();
            if (shown.startsWith(sdRoot.getAbsolutePath())) {
                shown = shown.substring(sdRoot.getAbsolutePath().length());
            }
            labels.add(rom.getName() + "   (" + (rom.length() / (1024 * 1024)) + " MB, " + shown + ")");
            actions.add(() -> deliverRom(rom));
        }
        if (systemPickerAvailable()) {
            labels.add("Browse with file picker…");
            actions.add(this::launchSystemPicker);
        }
        labels.add("Rescan");
        actions.add(this::showRomChooser);
        labels.add("Quit");
        actions.add(this::quitApp);

        String title = roms.isEmpty()
                ? "No rom found. Copy your OoT rom (.z64) to the SOH or Download folder, then Rescan."
                : "Select your Ocarina of Time rom";

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setCancelable(false)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    romChooserShowing = false;
                    actions.get(which).run();
                })
                .show();
    }

    private List<File> scanForRoms() {
        File sd = Environment.getExternalStorageDirectory();
        List<File> roots = new ArrayList<>();
        String[] names = { "SOH", "Download", "Downloads", "ROMs", "Roms", "roms", "N64", "n64",
                           "ROMs/N64", "ROMs/n64", "roms/n64", "roms/N64", "Roms/N64", "Emulation/roms/n64" };
        for (String n : names) roots.add(new File(sd, n));
        File appFiles = getExternalFilesDir(null);
        if (appFiles != null) roots.add(appFiles);

        // USB drives plugged into the Fire TV (or an OTG hub) show up under /storage.
        File[] volumes = new File("/storage").listFiles();
        if (volumes != null) {
            for (File v : volumes) {
                String n = v.getName();
                if (n.equals("emulated") || n.equals("self")) continue;
                roots.add(v);
            }
        }

        Map<String, File> found = new LinkedHashMap<>();
        for (File r : roots) scanDir(r, 2, found);
        // Last resort: the top of internal storage, no recursion.
        scanDir(sd, 0, found);
        return new ArrayList<>(found.values());
    }

    private void scanDir(File dir, int depth, Map<String, File> found) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth > 0 && !f.getName().startsWith(".")) scanDir(f, depth - 1, found);
            } else if (isRomFile(f)) {
                String key;
                try { key = f.getCanonicalPath(); } catch (IOException e) { key = f.getAbsolutePath(); }
                found.put(key, f);
            }
        }
    }

    private static boolean isRomFile(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return (n.endsWith(".z64") || n.endsWith(".n64") || n.endsWith(".v64"))
                && f.length() >= 16L * 1024 * 1024; // OoT roms are 32, 54 or 64 MB
    }

    private void deliverRom(File rom) {
        Log.i(TAG, "Using rom: " + rom.getAbsolutePath());
        nativeHandleSelectedFile(rom.getAbsolutePath());
    }

    private boolean systemPickerAvailable() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        return i.resolveActivity(getPackageManager()) != null;
    }

    private void launchSystemPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, FILE_PICKER_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this device", Toast.LENGTH_LONG).show();
            showRomChooser();
        }
    }

    // Copies a content:// rom into SOH/OOT.z64 off the UI thread, then hands it to the extractor.
    private void copyUriAndDeliver(Uri uri) {
        Toast.makeText(this, "Copying rom…", Toast.LENGTH_SHORT).show();
        BACKGROUND.execute(() -> {
            File destinationDirectory = new File(Environment.getExternalStorageDirectory(), "SOH");
            destinationDirectory.mkdirs();
            File destinationFile = new File(destinationDirectory, "OOT.z64");
            boolean ok = false;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(destinationFile)) {
                if (in != null) {
                    byte[] buffer = new byte[64 * 1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    ok = true;
                }
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Rom copy failed", e);
            }
            final boolean success = ok;
            runOnUiThread(() -> {
                if (success) {
                    deliverRom(destinationFile);
                } else {
                    Toast.makeText(this, "Couldn't read that rom", Toast.LENGTH_LONG).show();
                    showRomChooser();
                }
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_PICKER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                copyUriAndDeliver(data.getData());
            } else {
                // Cancelled: go back to the list instead of leaving the game waiting forever.
                showRomChooser();
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (hasStoragePermission()) {
                doVersionCheck();
                checkAndSetupFiles();
            } else {
                showStorageHelpDialog(false);
            }
        }
    }

    // Check if external storage is available and writable
    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    public native void attachController();
    public native void detachController();
    // Native method for setting button state
    public native void setButton(int button, boolean value);
    public native void setCameraState(int axis, float value);

    // Native method for setting joystick axis value
    public native void setAxis(int axis, short value);

    private Button button1, button2, button3, button4;
    private Button buttonA, buttonB, buttonX, buttonY;
    private Button buttonDpadUp, buttonDpadDown, buttonDpadLeft, buttonDpadRight;
    private Button buttonLB, buttonRB, buttonZ, buttonStart, buttonBack;
    private Button buttonToggle;
    private FrameLayout leftJoystick;
    private ImageView leftJoystickKnob;
    private View overlayView;

    // Function to set up the controller overlay (inflate layout and initialize buttons)
    private void setupControllerOverlay() {
        // Inflate the touchcontrol_overlay layout
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.touchcontrol_overlay, null);

        // Set layout params for overlayView to control positioning and sizing
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        overlayView.setLayoutParams(layoutParams);
        // Add overlay view to the main layout (you may need to add it to a container like FrameLayout)
        ViewGroup view = (ViewGroup) getContentView();
        view.addView(overlayView);
        view.setKeepScreenOn(true);

        final ViewGroup buttonGroup = overlayView.findViewById(R.id.button_group);

        buttonA = overlayView.findViewById(R.id.buttonA);
        buttonB = overlayView.findViewById(R.id.buttonB);
        buttonX = overlayView.findViewById(R.id.buttonX);
        buttonY = overlayView.findViewById(R.id.buttonY);

        buttonDpadUp = overlayView.findViewById(R.id.buttonDpadUp);
        buttonDpadDown = overlayView.findViewById(R.id.buttonDpadDown);
        buttonDpadLeft = overlayView.findViewById(R.id.buttonDpadLeft);
        buttonDpadRight = overlayView.findViewById(R.id.buttonDpadRight);

        buttonLB = overlayView.findViewById(R.id.buttonLB);
        buttonRB = overlayView.findViewById(R.id.buttonRB);
        buttonZ = overlayView.findViewById(R.id.buttonZ);

        buttonStart = overlayView.findViewById(R.id.buttonStart);
        buttonBack = overlayView.findViewById(R.id.buttonBack);

        buttonToggle = overlayView.findViewById(R.id.buttonToggle);

        // Initialize joysticks and joystick knobs from the inflated layout
        leftJoystick = overlayView.findViewById(R.id.left_joystick);
        leftJoystickKnob = overlayView.findViewById(R.id.left_joystick_knob);

        FrameLayout rightScreenArea = overlayView.findViewById(R.id.right_screen_area);

        // Set OnTouchListeners for the Xbox controller buttons
        addTouchListener(buttonA, ControllerButtons.BUTTON_A); // SDL Button 0 (A)
        addTouchListener(buttonB, ControllerButtons.BUTTON_B); // SDL Button 1 (B)
        addTouchListener(buttonX, ControllerButtons.BUTTON_X); // SDL Button 2 (X)
        addTouchListener(buttonY, ControllerButtons.BUTTON_Y); // SDL Button 3 (Y)

        setupCButtons(buttonDpadUp, ControllerButtons.AXIS_RY, 1); // SDL Button 10 (D-Pad Up)
        setupCButtons(buttonDpadDown, ControllerButtons.AXIS_RY , -1); // SDL Button 11 (D-Pad Down)
        setupCButtons(buttonDpadLeft, ControllerButtons.AXIS_RX, 1); // SDL Button 12 (D-Pad Left)
        setupCButtons(buttonDpadRight, ControllerButtons.AXIS_RX, -1); // SDL Button 13 (D-Pad Right)

        addTouchListener(buttonLB, ControllerButtons.BUTTON_LB); // SDL Button 4 (LB)
        addTouchListener(buttonRB, ControllerButtons.BUTTON_RB); // SDL Button 5 (RB)
        addTouchListener(buttonZ, ControllerButtons.AXIS_RT); // SDL Button 5 (Z)

        addTouchListener(buttonStart, ControllerButtons.BUTTON_START); // SDL Button 7 (Start)
        addTouchListener(buttonBack, ControllerButtons.BUTTON_BACK); // SDL Button 6 (Back)


        // Setup joystick movement
        setupJoystick(leftJoystick, leftJoystickKnob, true); // Left joystick

        setupLookAround(rightScreenArea);

        setupToggleButton(buttonToggle,buttonGroup);

    }

    private void setupToggleButton(Button button, ViewGroup uiGroup){
        boolean isHidden = preferences.getBoolean("controlsVisible", false); // Default to 'false' (visible)
        uiGroup.setVisibility(isHidden ? View.INVISIBLE : View.VISIBLE); // Set the initial visibility based on the saved state
        //if(isHidden){
        //    detachController();
        //}
        button.setOnClickListener(new View.OnClickListener() {
            boolean isHidden = false;
            @Override
            public void onClick(View v) {
                if (isHidden) {
                    uiGroup.setVisibility(View.VISIBLE); // Show UI elements
                    //attachController();
                } else {
                    uiGroup.setVisibility(View.INVISIBLE); // Hide UI elements
                    //detachController();
                }
                preferences.edit().putBoolean("controlsVisible", !isHidden).apply();
                isHidden = !isHidden; // Toggle state
            }
        });
    }

    // Function to set a touch listener for each button
    private void addTouchListener(Button button, int buttonNum) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        setButton(buttonNum, true);
                        button.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                        setButton(buttonNum, false);
                        button.setPressed(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        setButton(buttonNum, false);
                        return true;
                }
                return false;
            }
        });
    }

    private void setupCButtons(Button button, int buttonNum, int direction) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        setAxis(buttonNum, direction<0 ? Short.MAX_VALUE : Short.MIN_VALUE);
                        button.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                        setAxis(buttonNum, (short) 0);
                        button.setPressed(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        setAxis(buttonNum, (short) 0);
                        return true;
                }
                return false;
            }
        });
    }

    boolean TouchAreaEnabled = true;

    void DisableTouchArea(){
        TouchAreaEnabled = false;
    }
    void EnableTouchArea(){
        TouchAreaEnabled = true;
    }

    private void setupLookAround(FrameLayout rightScreenArea) {
        rightScreenArea.setOnTouchListener(new View.OnTouchListener() {
            private float lastX = 0;
            private float lastY = 0;
            private boolean isTouching = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Start tracking the finger's position
                        lastX = event.getX();
                        lastY = event.getY();
                        isTouching = true;
                        break;

                    case MotionEvent.ACTION_MOVE:
                        if (isTouching) {
                            // Calculate the change in position (delta)
                            float deltaX = event.getX() - lastX;
                            float deltaY = event.getY() - lastY;

                            // Update the last position
                            lastX = event.getX();
                            lastY = event.getY();

                            // Increase sensitivity by using a larger multiplier
                            // Adjust these multipliers to suit your needs
                            float sensitivityMultiplier = 15; // Higher value for more sensitivity
                            float rx = (deltaX * sensitivityMultiplier);
                            float ry = (deltaY * sensitivityMultiplier);

                            // Send the mapped values to the joystick axes
                            setCameraState(0, rx); // Right stick X axis
                            setCameraState(1, ry); // Right stick Y axis
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // Stop tracking the finger's position and reset joystick input
                        isTouching = false;
                        setCameraState(0, 0.0f); // Reset right stick X axis
                        setCameraState(1, 0.0f); // Reset right stick Y axis
                        break;
                }
                return TouchAreaEnabled; // Event full handled
            }
        });
    }





    // Function to set joystick movement with reset to center when not touched
    private void setupJoystick(FrameLayout joystickLayout, ImageView joystickKnob, boolean isLeft) {
        joystickLayout.post(() -> {
            // Calculate the joystick center once, before any events
            final float joystickCenterX = joystickLayout.getWidth() / 2f;
            final float joystickCenterY = joystickLayout.getHeight() / 2f;

            joystickLayout.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                        case MotionEvent.ACTION_MOVE:
                            // Calculate the joystick movement and move the knob
                            float deltaX = event.getX() - joystickCenterX;
                            float deltaY = event.getY() - joystickCenterY;

                            // Clamp the joystick movement to prevent it from going outside the area
                            float maxRadius = joystickLayout.getWidth() / 2f - joystickKnob.getWidth() / 2f;
                            float distance = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
                            if (distance > maxRadius) {
                                float scale = maxRadius / distance;
                                deltaX *= scale;
                                deltaY *= scale;
                            }

                            joystickKnob.setX(joystickCenterX + deltaX - joystickKnob.getWidth() / 2f);
                            joystickKnob.setY(joystickCenterY + deltaY - joystickKnob.getHeight() / 2f);

                            // Send joystick values to native C code
                            short x = (short) (deltaX / maxRadius * Short.MAX_VALUE);
                            short y = (short) (deltaY / maxRadius * Short.MAX_VALUE);

                            // Send X-axis and Y-axis values
                            setAxis(isLeft ? ControllerButtons.AXIS_LX : ControllerButtons.AXIS_RX, x); // X-axis
                            setAxis(isLeft ? ControllerButtons.AXIS_LY : ControllerButtons.AXIS_RY, y); // Y-axis
                            break;

                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            // Reset joystick knob to the center position (ensure it's placed correctly)
                            joystickKnob.setX(joystickCenterX - joystickKnob.getWidth() / 2f);
                            joystickKnob.setY(joystickCenterY - joystickKnob.getHeight() / 2f);

                            // Reset joystick values to 0 when released or canceled
                            setAxis(isLeft ? ControllerButtons.AXIS_LX : ControllerButtons.AXIS_RX, (short) 0); // X-axis
                            setAxis(isLeft ? ControllerButtons.AXIS_LY : ControllerButtons.AXIS_RY, (short) 0); // Y-axis
                            break;
                    }
                    return true;
                }
            });
        });


    }


}
