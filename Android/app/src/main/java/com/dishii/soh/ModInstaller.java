package com.dishii.soh;

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
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads GameBanana files and installs the mod archives inside them.
 *
 * Ship of Harkinian loads every .otr/.o2r (and .zip/.mpq) under SOH/mods, including subfolders, in
 * alphabetical path order. A GameBanana download is usually a packaging archive (zip, 7z or rar)
 * that contains the real .otr/.o2r, so it is unpacked and only those files are kept, in
 * SOH/mods/&lt;mod name&gt;/. Disabled mods are moved to SOH/mods_disabled/ where the game can't see them.
 */
final class ModInstaller {

    interface Progress {
        void onProgress(String stage, long done, long total);
    }

    static final class InstalledMod {
        File location;          // folder (or single loose file) inside mods/ or mods_disabled/
        String name;
        boolean enabled;
        long sizeBytes;
        int gameBananaId;       // 0 if installed by hand
        String version = "";
    }

    private static final String MANIFEST = ".gamebanana.json";

    static File modsDir() { return new File(SohConfig.sohRoot(), "mods"); }
    static File disabledDir() { return new File(SohConfig.sohRoot(), "mods_disabled"); }

    // ---- Install ------------------------------------------------------------------------------

    /** Returns the number of mod archives installed. Blocking; call off the UI thread. */
    static int install(GameBananaApi.ModSummary mod, GameBananaApi.ModFile file, File cacheDir, Progress progress)
            throws IOException {
        File work = new File(cacheDir, "gb-" + file.id);
        deleteRecursive(work);
        if (!work.mkdirs()) throw new IOException("Couldn't create " + work);
        try {
            File download = new File(work, safeName(file.name));
            downloadTo(file.downloadUrl, download, file.size, progress);

            if (file.md5 != null && !file.md5.isEmpty()) {
                progress.onProgress("Checking file…", 0, 0);
                String actual = md5(download);
                if (!actual.isEmpty() && !actual.equalsIgnoreCase(file.md5)) {
                    throw new IOException("Download was corrupted (checksum mismatch). Try again.");
                }
            }

            File target = new File(modsDir(), safeName(mod.name));
            // Reinstall/update: clear any previous copy, enabled or disabled.
            deleteRecursive(target);
            deleteRecursive(new File(disabledDir(), target.getName()));
            if (!target.mkdirs()) throw new IOException("Couldn't create " + target);

            progress.onProgress("Unpacking…", 0, 0);
            int count = extractModArchives(download, target);
            if (count == 0) {
                deleteRecursive(target);
                throw new IOException("No .otr or .o2r mod files were found in " + file.name
                        + ". It may be a mod for a different game version or need manual setup.");
            }
            writeManifest(target, mod, file);
            return count;
        } finally {
            deleteRecursive(work);
        }
    }

    private static void downloadTo(String url, File dest, long expectedSize, Progress progress) throws IOException {
        HttpURLConnection c = GameBananaApi.open(url);
        c.setRequestProperty("Accept", "*/*");
        c.setReadTimeout(60000);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Download failed (HTTP " + code + ")");
            long total = c.getContentLength() > 0 ? c.getContentLength() : expectedSize;
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0, lastReport = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done - lastReport > 256 * 1024 || done == total) {
                        lastReport = done;
                        progress.onProgress("Downloading…", done, total);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Copies every .otr/.o2r inside the download into the target folder. */
    static int extractModArchives(File download, File target) throws IOException {
        String lower = download.getName().toLowerCase(Locale.ROOT);
        if (isModArchive(lower)) {
            copyFile(download, new File(target, download.getName()));
            return 1;
        }
        byte[] head = readHead(download, 8);
        if (lower.endsWith(".zip") || (head[0] == 'P' && head[1] == 'K')) return extractZip(download, target);
        if (lower.endsWith(".7z") || (head[0] == '7' && head[1] == 'z')) return extract7z(download, target);
        if (lower.endsWith(".rar") || (head[0] == 'R' && head[1] == 'a' && head[2] == 'r')) return extractRar(download, target);
        throw new IOException("Unsupported file type: " + download.getName());
    }

    private static int extractZip(File zip, File target) throws IOException {
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = baseName(e.getName());
                if (isModArchive(name)) {
                    writeStream(zin, uniqueFile(target, name));
                    count++;
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip") || name.toLowerCase(Locale.ROOT).endsWith(".7z")) {
                    // Archive inside an archive (common for "pick a variant" packs): unpack it too.
                    File nested = File.createTempFile("nested", "_" + name, target.getParentFile());
                    try {
                        writeStream(zin, nested);
                        count += extractModArchives(nested, target);
                    } catch (IOException ignored) {
                        // not a usable archive; skip
                    } finally {
                        nested.delete();
                    }
                }
            }
        }
        return count;
    }

    private static int extract7z(File archive, File target) throws IOException {
        int count = 0;
        try (SevenZFile z = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || !e.hasStream()) continue;
                String name = baseName(e.getName());
                if (!isModArchive(name)) continue;
                try (OutputStream out = new FileOutputStream(uniqueFile(target, name))) {
                    int n;
                    while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                }
                count++;
            }
        }
        return count;
    }

    private static int extractRar(File rar, File target) throws IOException {
        int count = 0;
        try (Archive archive = new Archive(rar)) {
            for (FileHeader h : archive.getFileHeaders()) {
                if (h.isDirectory()) continue;
                String name = baseName(h.getFileName());
                if (!isModArchive(name)) continue;
                try (OutputStream out = new FileOutputStream(uniqueFile(target, name))) {
                    archive.extractFile(h, out);
                }
                count++;
            }
        } catch (com.github.junrar.exception.UnsupportedRarV5Exception e) {
            throw new IOException("This mod is packed as RAR 5, which the TV app can't unpack. "
                    + "Pick a .zip/.7z file if the mod has one, or install it from a computer.");
        } catch (com.github.junrar.exception.RarException e) {
            throw new IOException("Couldn't unpack the RAR file: " + e.getMessage());
        }
        return count;
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
            if (f.isFile() && !isModArchive(f.getName())) continue;
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
                if (countModFiles(f) == 0) continue; // empty folder, not a mod
            }
            out.add(m);
        }
    }

    static void setEnabled(InstalledMod m, boolean enabled) throws IOException {
        if (m.enabled == enabled) return;
        File destDir = enabled ? modsDir() : disabledDir();
        destDir.mkdirs();
        File dest = new File(destDir, m.location.getName());
        deleteRecursive(dest);
        if (!m.location.renameTo(dest)) throw new IOException("Couldn't move " + m.location.getName());
        m.location = dest;
        m.enabled = enabled;
    }

    static void delete(InstalledMod m) throws IOException {
        deleteRecursive(m.location);
        if (m.location.exists()) throw new IOException("Couldn't delete " + m.location.getName());
    }

    /** Folder names of installed GameBanana mods by id, to mark "Installed" in the browser. */
    static java.util.Map<Integer, InstalledMod> installedById() {
        java.util.Map<Integer, InstalledMod> map = new java.util.HashMap<>();
        for (InstalledMod m : listInstalled()) if (m.gameBananaId != 0) map.put(m.gameBananaId, m);
        return map;
    }

    // ---- Helpers ------------------------------------------------------------------------------

    static boolean isModArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".otr") || n.endsWith(".o2r");
    }

    private static int countModFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) n += f.isDirectory() ? countModFiles(f) : (isModArchive(f.getName()) ? 1 : 0);
        return n;
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
