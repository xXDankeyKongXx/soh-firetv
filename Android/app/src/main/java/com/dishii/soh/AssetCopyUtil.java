package com.dishii.soh;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AssetCopyUtil {

    /** Called with the running count of copied files (from a worker thread). */
    public interface Progress {
        void onCopied(int copied, int total);
    }

    public static void copyAssetsToExternal(Context context, String assetsFolderPath, String externalFolderPath) throws IOException {
        copyAssetsToExternal(context, assetsFolderPath, externalFolderPath, null);
    }

    /**
     * Copies an assets folder to storage. The original version called AssetManager.list() on every
     * file, which rescans the APK each time; with ~7,700 extractor files that took many minutes on
     * a Fire TV. This lists each directory once, then copies the files on a few threads.
     */
    public static void copyAssetsToExternal(Context context, String assetsFolderPath, String externalFolderPath,
                                            Progress progress) throws IOException {
        AssetManager assetManager = context.getAssets();
        // A marker written only after a full copy. Without it, an earlier copy may have been cut off
        // half-way (app closed during setup), so overwrite everything instead of skipping existing files.
        final File doneMarker = new File(externalFolderPath, ".copy_complete");
        final boolean overwrite = !doneMarker.exists();
        java.util.List<String[]> files = new java.util.ArrayList<>(); // {assetPath, externalPath}
        collect(assetManager, assetsFolderPath, externalFolderPath, files);

        final int total = files.size();
        final java.util.concurrent.atomic.AtomicInteger copied = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<IOException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        for (final String[] f : files) {
            pool.execute(() -> {
                if (failure.get() != null) return;
                File externalFile = new File(f[1]);
                if (overwrite || !externalFile.exists()) {
                    try (InputStream in = assetManager.open(f[0]);
                         OutputStream out = new FileOutputStream(externalFile)) {
                        byte[] buffer = new byte[16 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    } catch (IOException e) {
                        failure.compareAndSet(null, e);
                        return;
                    }
                }
                int n = copied.incrementAndGet();
                if (progress != null && (n % 50 == 0 || n == total)) progress.onCopied(n, total);
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (failure.get() != null) throw failure.get();
        if (copied.get() == total) {
            doneMarker.createNewFile();
        }
    }

    // Walk the asset tree, listing each directory once and creating the matching folders.
    private static void collect(AssetManager am, String assetDir, String externalDir, java.util.List<String[]> out)
            throws IOException {
        String[] names = am.list(assetDir);
        if (names == null) return;
        new File(externalDir).mkdirs();
        for (String name : names) {
            String assetPath = assetDir + File.separator + name;
            String externalPath = externalDir + File.separator + name;
            // Files have a dot extension in this asset set; folders don't. Fall back to a list() check
            // only for names without an extension.
            boolean isDir = name.indexOf('.') < 0 && am.list(assetPath).length > 0;
            if (isDir) {
                collect(am, assetPath, externalPath, out);
            } else {
                out.add(new String[] { assetPath, externalPath });
            }
        }
    }

    public static void copyDirectory(File sourceDir, File targetDir) throws IOException {
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        for (File file : sourceDir.listFiles()) {
            File dest = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                copyDirectory(file, dest);
            } else {
                copyFile(file, dest);
            }
        }
    }

    public static void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64*1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}