package com.dishii.soh;

import android.os.Build;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads GameBanana files and installs the mod archives inside them.
 *
 * Ship of Harkinian loads every .otr/.o2r/.zip/.mpq under SOH/mods, including subfolders, in
 * alphabetical path order. A GameBanana download is usually a packaging archive (zip, 7z or rar)
 * that contains the real .otr/.o2r, so it's unpacked and only those files are kept.
 *
 * Installs are two-step so a failure never leaves half a mod where the game can load it:
 *   prepare(): download to the app cache, verify, unpack into SOH/.staging/ (not read by the game)
 *   commit():  keep the files the user picked, then move the folder into SOH/mods/&lt;mod name&gt;/
 * Disabled mods live in SOH/mods_disabled/, where the game can't see them.
 */
final class ModInstaller {

    interface Progress {
        void onProgress(String stage, long done, long total);
    }

    static final class CancelledException extends IOException {
        CancelledException() { super("Cancelled"); }
    }

    static final class InstalledMod {
        File location;          // folder (or single loose file) inside mods/ or mods_disabled/
        String name;
        boolean enabled;
        long sizeBytes;
        int gameBananaId;       // 0 if installed by hand
        String version = "";
    }

    static final class StagedFile {
        final File file; final String label; // label = path inside the download, for choosing variants
        StagedFile(File file, String label) { this.file = file; this.label = label; }
    }

    static final class Staged {
        final File dir; final List<StagedFile> files = new ArrayList<>();
        final GameBananaApi.ModSummary mod; final GameBananaApi.ModFile file;
        Staged(File dir, GameBananaApi.ModSummary mod, GameBananaApi.ModFile file) {
            this.dir = dir; this.mod = mod; this.file = file;
        }
    }

    private static final String MANIFEST = ".gamebanana.json";
    private static final int MAX_NESTING = 2;
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    static File modsDir() { return new File(SohConfig.sohRoot(), "mods"); }
    static File disabledDir() { return new File(SohConfig.sohRoot(), "mods_disabled"); }
    static File stagingRoot() { return new File(SohConfig.sohRoot(), ".staging"); }

    /** One install at a time (also across launcher re-creation). */
    static boolean tryBegin() { return BUSY.compareAndSet(false, true); }
    static void end() { BUSY.set(false); }

    /** Remove leftovers from an install that was interrupted (app killed, TV turned off). */
    static void cleanupStaging(File cacheDir) {
        if (!BUSY.get()) {
            deleteRecursive(stagingRoot());
            File[] old = cacheDir.listFiles((d, n) -> n.startsWith("gb-"));
            if (old != null) for (File f : old) deleteRecursive(f);
        }
    }

    // ---- Install ------------------------------------------------------------------------------

    /** Download, verify and unpack. Blocking; call off the UI thread. */
    static Staged prepare(GameBananaApi.ModSummary mod, GameBananaApi.ModFile file, File cacheDir,
                          Progress progress, AtomicBoolean cancel) throws IOException {
        String nonce = file.id + "-" + System.currentTimeMillis();
        File work = new File(cacheDir, "gb-" + nonce);
        File stage = new File(stagingRoot(), nonce);
        deleteRecursive(work);
        if (!work.mkdirs()) throw new IOException("Couldn't create a download folder");
        boolean ok = false;
        try {
            File download = new File(work, safeName(file.name));
            downloadTo(file.downloadUrl, download, file.size, progress, cancel);

            if (file.md5 != null && !file.md5.isEmpty()) {
                progress.onProgress("Checking file…", 0, 0);
                String actual = md5(download);
                if (!actual.isEmpty() && !actual.equalsIgnoreCase(file.md5)) {
                    throw new IOException("Download was corrupted (checksum mismatch). Try again.");
                }
            }
            checkFreeSpace(download.length() * 3);

            if (!stage.mkdirs()) throw new IOException("Couldn't create " + stage);
            Staged staged = new Staged(stage, mod, file);
            progress.onProgress("Unpacking…", 0, 0);
            extract(download, download.getName(), staged, work, 0, cancel);
            if (staged.files.isEmpty()) {
                throw new IOException("No .otr or .o2r mod files were found in " + file.name
                        + ". It may be a mod for a different game version or need manual setup.");
            }
            ok = true;
            return staged;
        } finally {
            deleteRecursive(work);
            if (!ok) deleteRecursive(stage);
        }
    }

    /** Keep the chosen files and move the mod into SOH/mods. Returns the installed folder. */
    static File commit(Staged staged, Collection<StagedFile> keep) throws IOException {
        try {
            Set<File> keepFiles = new HashSet<>();
            for (StagedFile f : keep) keepFiles.add(f.file);
            for (StagedFile f : staged.files) if (!keepFiles.contains(f.file)) f.file.delete();
            if (keepFiles.isEmpty()) throw new IOException("No files selected");
            writeManifest(staged.dir, staged.mod, staged.file);

            modsDir().mkdirs();
            // Replace only an earlier install of this same GameBanana mod; never someone else's folder.
            // The old copy is parked in staging first, so a failed move can put it back.
            List<File[]> parked = new ArrayList<>(); // {original, parked}
            for (File dir : new File[] { modsDir(), disabledDir() }) {
                File[] entries = dir.listFiles();
                if (entries == null) continue;
                for (File e : entries) {
                    if (e.isDirectory() && manifestId(e) == staged.mod.id) {
                        File p = new File(stagingRoot(), "old-" + System.nanoTime());
                        if (e.renameTo(p)) parked.add(new File[] { e, p });
                    }
                }
            }
            File target = new File(modsDir(), safeName(staged.mod.name));
            if (target.exists() || new File(disabledDir(), target.getName()).exists()) {
                target = new File(modsDir(), safeName(staged.mod.name) + " [" + staged.mod.id + "]");
            }
            if (target.exists() || !staged.dir.renameTo(target)) {
                for (File[] pr : parked) pr[1].renameTo(pr[0]); // restore the previous version
                throw new IOException("Couldn't move the mod into place");
            }
            for (File[] pr : parked) deleteRecursive(pr[1]);
            return target;
        } finally {
            deleteRecursive(staged.dir);
        }
    }

    static void discard(Staged staged) {
        if (staged != null) deleteRecursive(staged.dir);
    }

    private static void downloadTo(String url, File dest, long expectedSize, Progress progress, AtomicBoolean cancel)
            throws IOException {
        HttpURLConnection c = GameBananaApi.open(url);
        c.setRequestProperty("Accept", "*/*");
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Download failed (HTTP " + code + ")");
            long header = c.getContentLengthLong();
            long total = header > 0 ? header : expectedSize;
            long done = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                long lastReport = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel.get()) throw new CancelledException();
                    out.write(buf, 0, n);
                    done += n;
                    if (done - lastReport > 256 * 1024 || done == total) {
                        lastReport = done;
                        progress.onProgress("Downloading…", done, total);
                    }
                }
            }
            if (header > 0 && done != header) throw new IOException("Download was cut off. Try again.");
        } finally {
            c.disconnect();
        }
    }

    /** Collects every .otr/.o2r inside the download into the staging folder. */
    private static void extract(File archive, String label, Staged staged, File work, int depth, AtomicBoolean cancel)
            throws IOException {
        if (cancel.get()) throw new CancelledException();
        String lower = archive.getName().toLowerCase(Locale.ROOT);
        if (isModArchive(lower)) {
            File dest = uniqueFile(staged.dir, archive.getName());
            copyFile(archive, dest);
            staged.files.add(new StagedFile(dest, label));
            return;
        }
        byte[] head = readHead(archive, 8);
        if (lower.endsWith(".zip") || (head[0] == 'P' && head[1] == 'K')) { extractZip(archive, label, staged, work, depth, cancel); return; }
        if (lower.endsWith(".7z") || (head[0] == '7' && head[1] == 'z')) { needsApi26("7z"); extract7z(archive, label, staged, cancel); return; }
        if (lower.endsWith(".rar") || (head[0] == 'R' && head[1] == 'a' && head[2] == 'r')) { needsApi26("RAR"); extractRar(archive, label, staged, cancel); return; }
        throw new IOException("Unsupported file type: " + archive.getName());
    }

    private static void needsApi26(String kind) throws IOException {
        // commons-compress and junrar use java.nio.file / java.time, added in Android 8.
        if (Build.VERSION.SDK_INT < 26) {
            throw new IOException("Unpacking " + kind + " files needs Android 8 (Fire OS 7) or newer.");
        }
    }

    private static void extractZip(File zip, String label, Staged staged, File work, int depth, AtomicBoolean cancel)
            throws IOException {
        // ZipFile reads the central directory, so it copes with data descriptors; names fall back to
        // a lenient decoding instead of failing on non-UTF-8 (Windows) file names.
        try (ZipFile zf = openZip(zip)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                if (cancel.get()) throw new CancelledException();
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = baseName(e.getName());
                String nlower = name.toLowerCase(Locale.ROOT);
                if (isModArchive(name)) {
                    File dest = uniqueFile(staged.dir, name);
                    try (InputStream in = zf.getInputStream(e)) { writeStream(in, dest); }
                    staged.files.add(new StagedFile(dest, label + " › " + e.getName()));
                } else if (depth < MAX_NESTING && (nlower.endsWith(".zip") || nlower.endsWith(".7z") || nlower.endsWith(".rar"))) {
                    // Archive inside an archive (common for "pick a variant" packs). Unpack it in the
                    // cache work folder, never inside SOH/mods.
                    File nested = new File(work, "nested-" + depth + "-" + System.nanoTime() + "-" + name);
                    try {
                        try (InputStream in = zf.getInputStream(e)) { writeStream(in, nested); }
                        extract(nested, label + " › " + e.getName(), staged, work, depth + 1, cancel);
                    } catch (CancelledException c) {
                        throw c;
                    } catch (IOException ignored) {
                        // not a usable archive; skip it
                    } finally {
                        nested.delete();
                    }
                }
            }
        }
    }

    private static ZipFile openZip(File zip) throws IOException {
        try {
            return new ZipFile(zip, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return new ZipFile(zip, java.nio.charset.Charset.forName("IBM437"));
        }
    }

    private static void extract7z(File archive, String label, Staged staged, AtomicBoolean cancel) throws IOException {
        try (SevenZFile z = SevenZFile.builder().setFile(archive).setMaxMemoryLimitKb(128 * 1024).get()) {
            SevenZArchiveEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                if (cancel.get()) throw new CancelledException();
                if (e.isDirectory() || !e.hasStream()) continue;
                String name = baseName(e.getName());
                if (!isModArchive(name)) continue;
                File dest = uniqueFile(staged.dir, name);
                try (OutputStream out = new FileOutputStream(dest)) {
                    int n;
                    while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                }
                staged.files.add(new StagedFile(dest, label + " › " + e.getName()));
            }
        } catch (org.apache.commons.compress.MemoryLimitException e) {
            throw new IOException("This 7z file needs more memory to unpack than the TV can spare.");
        }
    }

    private static void extractRar(File rar, String label, Staged staged, AtomicBoolean cancel) throws IOException {
        try (Archive archive = new Archive(rar)) {
            // In a solid RAR every file depends on the ones before it, so all of them have to be
            // decoded in order; non-mod files are decoded and thrown away.
            boolean solid = archive.getMainHeader() != null && archive.getMainHeader().isSolid();
            for (FileHeader h : archive.getFileHeaders()) {
                if (cancel.get()) throw new CancelledException();
                if (h.isDirectory()) continue;
                String name = baseName(h.getFileName());
                if (isModArchive(name)) {
                    File dest = uniqueFile(staged.dir, name);
                    try (OutputStream out = new FileOutputStream(dest)) {
                        archive.extractFile(h, out);
                    }
                    staged.files.add(new StagedFile(dest, label + " › " + h.getFileName()));
                } else if (solid) {
                    archive.extractFile(h, new OutputStream() {
                        @Override public void write(int b) { }
                        @Override public void write(byte[] b, int off, int len) { }
                    });
                }
            }
        } catch (com.github.junrar.exception.UnsupportedRarV5Exception e) {
            throw new IOException("This mod is packed as RAR 5, which the TV app can't unpack. "
                    + "Pick a .zip/.7z file if the mod has one, or install it from a computer.");
        } catch (com.github.junrar.exception.RarException e) {
            throw new IOException("Couldn't unpack the RAR file: " + e.getMessage());
        }
    }

    // ---- Installed mods -----------------------------------------------------------------------

    static List<InstalledMod> listInstalled() {
        List<InstalledMod> out = new ArrayList<>();
        addInstalled(modsDir(), true, out);
        addInstalled(disabledDir(), false, out);
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static void addInstalled(File dir, boolean enabled, List<InstalledMod> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        Arrays.sort(entries);
        for (File f : entries) {
            if (f.getName().startsWith(".")) continue;
            if (f.isFile() && !isLoadable(f.getName())) continue;
            if (f.isDirectory() && countLoadable(f) == 0) continue; // empty folder, not a mod
            InstalledMod m = new InstalledMod();
            m.location = f;
            m.name = f.getName();
            m.enabled = enabled;
            m.sizeBytes = sizeOf(f);
            if (f.isDirectory()) {
                File manifest = new File(f, MANIFEST);
                if (manifest.exists()) {
                    try {
                        JSONObject j = new JSONObject(SohConfig.readText(manifest));
                        m.name = j.optString("name", m.name);
                        m.gameBananaId = j.optInt("gameBananaId");
                        m.version = j.optString("version", "");
                    } catch (IOException | JSONException ignored) { }
                }
            }
            out.add(m);
        }
    }

    static void setEnabled(InstalledMod m, boolean enabled) throws IOException {
        if (m.enabled == enabled) return;
        File destDir = enabled ? modsDir() : disabledDir();
        destDir.mkdirs();
        File dest = new File(destDir, m.location.getName());
        if (dest.exists()) {
            throw new IOException("A mod named \"" + dest.getName() + "\" is already "
                    + (enabled ? "enabled" : "disabled") + ". Remove one of them first.");
        }
        if (!m.location.renameTo(dest)) throw new IOException("Couldn't move " + m.location.getName());
        m.location = dest;
        m.enabled = enabled;
    }

    static void delete(InstalledMod m) throws IOException {
        deleteRecursive(m.location);
        if (m.location.exists()) throw new IOException("Couldn't delete " + m.location.getName());
    }

    /** GameBanana ids of installed mods, to mark "Installed" in the browser. */
    static Set<Integer> installedIds() {
        Set<Integer> ids = new HashSet<>();
        for (InstalledMod m : listInstalled()) if (m.gameBananaId != 0) ids.add(m.gameBananaId);
        return ids;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /**
     * True if any of these mod files replaces "alternate assets" (paths under alt/), which the game
     * only shows while its Use Alternate Assets setting is on. .o2r files are zips and are checked;
     * legacy .otr (MPQ) files can't be inspected here, so they're assumed to need it.
     */
    static boolean usesAltAssets(Collection<File> files) {
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".otr")) return true;
            if (n.endsWith(".o2r") || n.endsWith(".zip")) {
                try (ZipFile z = openZip(f)) {
                    Enumeration<? extends ZipEntry> e = z.entries();
                    while (e.hasMoreElements()) {
                        if (e.nextElement().getName().replace('\\', '/').startsWith("alt/")) return true;
                    }
                } catch (IOException | RuntimeException ignored) { }
            }
        }
        return false;
    }

    /** Files the mod installer pulls out of downloads. */
    static boolean isModArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".otr") || n.endsWith(".o2r");
    }

    /** Everything the game itself loads from SOH/mods. */
    static boolean isLoadable(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return isModArchive(n) || n.endsWith(".zip") || n.endsWith(".mpq");
    }

    private static int countLoadable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) n += f.isDirectory() ? countLoadable(f) : (isLoadable(f.getName()) ? 1 : 0);
        return n;
    }

    private static int manifestId(File dir) {
        File manifest = new File(dir, MANIFEST);
        if (!manifest.exists()) return 0;
        try {
            return new JSONObject(SohConfig.readText(manifest)).optInt("gameBananaId");
        } catch (IOException | JSONException e) {
            return 0;
        }
    }

    private static void writeManifest(File dir, GameBananaApi.ModSummary mod, GameBananaApi.ModFile file) {
        try {
            JSONObject j = new JSONObject();
            j.put("gameBananaId", mod.id);
            j.put("name", mod.name);
            j.put("version", mod.version == null ? "" : mod.version);
            j.put("file", file.name);
            j.put("fileId", file.id);
            j.put("installedAt", System.currentTimeMillis());
            try (OutputStream out = new FileOutputStream(new File(dir, MANIFEST))) {
                out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException ignored) { }
    }

    private static void checkFreeSpace(long needed) throws IOException {
        long free = SohConfig.sohRoot().getParentFile() != null
                ? SohConfig.sohRoot().getParentFile().getUsableSpace() : Long.MAX_VALUE;
        if (free > 0 && free < needed) {
            throw new IOException("Not enough free space on the TV (" + formatSize(free) + " free, about "
                    + formatSize(needed) + " needed).");
        }
    }

    /** Last path segment only: archive entries can never write outside the target folder. */
    private static String baseName(String entryName) {
        String n = entryName.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        return safeName(slash >= 0 ? n.substring(slash + 1) : n);
    }

    static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 60) s = s.substring(0, 60).trim();
        return s.isEmpty() ? "mod" : s;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        int i = 2;
        while (f.exists()) {
            int dot = name.lastIndexOf('.');
            f = new File(dir, (dot > 0 ? name.substring(0, dot) : name) + " (" + i++ + ")" + (dot > 0 ? name.substring(dot) : ""));
        }
        return f;
    }

    private static void writeStream(InputStream in, File dest) throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src)) { writeStream(in, dest); }
    }

    private static byte[] readHead(File f, int n) throws IOException {
        byte[] b = new byte[n];
        try (InputStream in = new FileInputStream(f)) {
            int r = in.read(b);
            if (r < 3) Arrays.fill(b, (byte) 0);
        }
        return b;
    }

    private static String md5(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] files = f.listFiles();
        if (files != null) for (File c : files) total += sizeOf(c);
        return total;
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }

    static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format(Locale.ROOT, "%d KB", bytes / 1024);
        return bytes + " B";
    }

    private ModInstaller() { }
}
