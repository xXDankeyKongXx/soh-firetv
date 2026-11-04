package com.dishii.soh;

import android.net.Uri;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal GameBanana apiv11 client for Ship of Harkinian (game 16121).
 * Endpoints and fields were checked against live API responses:
 *   Mod/Index           browse mods only (the game Subfeed also mixes in requests/questions)
 *   Util/Search/Results search, scoped to SoH and to mods
 *   Mod/Categories      category list with item counts
 *   Mod/{id}/ProfilePage description, version, download count, files
 *   Mod/{id}/Files      downloadable files with size, md5 and virus-scan result
 * Network calls block: run them off the UI thread.
 */
final class GameBananaApi {

    static final int SOH_GAME_ID = 16121;
    static final int PER_PAGE = 15;
    private static final String BASE = "https://gamebanana.com/apiv11";
    static final String USER_AGENT = "ShipOfHarkinian-FireTV/1.0";

    enum Sort {
        NEWEST("Generic_Newest", "Newest"),
        MOST_DOWNLOADED("Generic_MostDownloaded", "Most downloaded"),
        MOST_LIKED("Generic_MostLiked", "Most liked");

        final String api, label;
        Sort(String api, String label) { this.api = api; this.label = label; }
    }

    static final class Category {
        final int id; final String name; final int count;
        Category(int id, String name, int count) { this.id = id; this.name = name; this.count = count; }
    }

    static final class ModSummary {
        int id; String name, author, category, thumbUrl, version, profileUrl;
        int likes, views; long dateUpdated; boolean hasFiles, obsolete;
    }

    static final class ModFile {
        int id; String name, downloadUrl, md5, analysis, antivirus;
        long size; int downloads;
        boolean looksSafe() {
            boolean scanOk = analysis == null || analysis.isEmpty() || "ok".equalsIgnoreCase(analysis);
            boolean avOk = antivirus == null || antivirus.isEmpty() || "clean".equalsIgnoreCase(antivirus);
            return scanOk && avOk;
        }
    }

    static final class ModDetails {
        String description, text, version, imageUrl;
        int downloads, likes, views;
        final List<ModFile> files = new ArrayList<>();
    }

    static final class Page {
        final List<ModSummary> mods = new ArrayList<>();
        int total; boolean complete;
        int pageCount() { return Math.max(1, (total + PER_PAGE - 1) / PER_PAGE); }
    }

    // ---- Requests -----------------------------------------------------------------------------

    static Page browse(int page, Sort sort, int categoryId) throws IOException, JSONException {
        Uri.Builder b = Uri.parse(BASE + "/Mod/Index").buildUpon()
                .appendQueryParameter("_nPage", String.valueOf(page))
                .appendQueryParameter("_nPerpage", String.valueOf(PER_PAGE))
                .appendQueryParameter("_aFilters[Generic_Game]", String.valueOf(SOH_GAME_ID))
                .appendQueryParameter("_sSort", sort.api);
        if (categoryId > 0) b.appendQueryParameter("_aFilters[Generic_Category]", String.valueOf(categoryId));
        return parsePage(new JSONObject(get(b.build().toString())));
    }

    static Page search(String query, int page) throws IOException, JSONException {
        Uri u = Uri.parse(BASE + "/Util/Search/Results").buildUpon()
                .appendQueryParameter("_sModelName", "Mod")
                .appendQueryParameter("_sSearchString", query)
                .appendQueryParameter("_nPage", String.valueOf(page))
                .appendQueryParameter("_nPerpage", String.valueOf(PER_PAGE))
                .appendQueryParameter("_idGameRow", String.valueOf(SOH_GAME_ID))
                .build();
        return parsePage(new JSONObject(get(u.toString())));
    }

    static List<Category> categories() throws IOException, JSONException {
        Uri u = Uri.parse(BASE + "/Mod/Categories").buildUpon()
                .appendQueryParameter("_idGameRow", String.valueOf(SOH_GAME_ID))
                .appendQueryParameter("_sSort", "a_to_z")
                .appendQueryParameter("_bShowEmpty", "false")
                .build();
        String body = get(u.toString());
        JSONArray arr = body.trim().startsWith("[") ? new JSONArray(body)
                : new JSONObject(body).optJSONArray("_aRecords");
        List<Category> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            out.add(new Category(c.optInt("_idRow"), c.optString("_sName"), c.optInt("_nItemCount")));
        }
        return out;
    }

    static ModDetails details(int modId) throws IOException, JSONException {
        JSONObject p = new JSONObject(get(BASE + "/Mod/" + modId + "/ProfilePage"));
        ModDetails d = new ModDetails();
        d.description = p.optString("_sDescription", "");
        d.text = htmlToText(p.optString("_sText", ""));
        d.version = p.optString("_sVersion", "");
        d.downloads = p.optInt("_nDownloadCount");
        d.likes = p.optInt("_nLikeCount");
        d.views = p.optInt("_nViewCount");
        d.imageUrl = imageUrl(p.optJSONObject("_aPreviewMedia"), true);
        JSONArray files = p.optJSONArray("_aFiles");
        if (files == null) {
            String f = get(BASE + "/Mod/" + modId + "/Files");
            files = f.trim().startsWith("[") ? new JSONArray(f) : new JSONArray();
        }
        for (int i = 0; i < files.length(); i++) d.files.add(parseFile(files.getJSONObject(i)));
        return d;
    }

    // ---- Parsing ------------------------------------------------------------------------------

    private static Page parsePage(JSONObject o) throws JSONException {
        if (o.has("_sErrorCode")) throw new JSONException("GameBanana error: " + o.optString("_sErrorCode"));
        Page page = new Page();
        JSONObject meta = o.optJSONObject("_aMetadata");
        if (meta != null) {
            page.total = meta.optInt("_nRecordCount");
            page.complete = meta.optBoolean("_bIsComplete", true);
        }
        JSONArray recs = o.optJSONArray("_aRecords");
        if (recs == null) return page;
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.getJSONObject(i);
            if (!"Mod".equals(r.optString("_sModelName", "Mod"))) continue;
            JSONObject game = r.optJSONObject("_aGame");
            if (game != null && game.optInt("_idRow", SOH_GAME_ID) != SOH_GAME_ID) continue;
            ModSummary m = new ModSummary();
            m.id = r.optInt("_idRow");
            m.name = r.optString("_sName", "Mod #" + m.id);
            JSONObject sub = r.optJSONObject("_aSubmitter");
            m.author = sub == null ? "" : sub.optString("_sName", "");
            JSONObject cat = r.optJSONObject("_aRootCategory");
            m.category = cat == null ? "" : cat.optString("_sName", "");
            m.thumbUrl = imageUrl(r.optJSONObject("_aPreviewMedia"), false);
            m.version = r.optString("_sVersion", "");
            m.profileUrl = r.optString("_sProfileUrl", "https://gamebanana.com/mods/" + m.id);
            m.likes = r.optInt("_nLikeCount");
            m.views = r.optInt("_nViewCount");
            m.dateUpdated = r.optLong("_tsDateUpdated", r.optLong("_tsDateModified"));
            m.hasFiles = r.optBoolean("_bHasFiles", true);
            m.obsolete = r.optBoolean("_bIsObsolete", false);
            page.mods.add(m);
        }
        return page;
    }

    private static ModFile parseFile(JSONObject f) {
        ModFile m = new ModFile();
        m.id = f.optInt("_idRow");
        m.name = f.optString("_sFile", "file-" + m.id);
        m.size = f.optLong("_nFilesize");
        m.downloads = f.optInt("_nDownloadCount");
        m.downloadUrl = f.optString("_sDownloadUrl", "https://gamebanana.com/dl/" + m.id);
        m.md5 = f.optString("_sMd5Checksum", "");
        m.analysis = f.optString("_sAnalysisResult", "");
        m.antivirus = f.optString("_sAvResult", "");
        return m;
    }

    /** First screenshot: 220px-wide for list thumbnails, 530px for the details view. */
    private static String imageUrl(JSONObject media, boolean large) {
        if (media == null) return null;
        JSONArray images = media.optJSONArray("_aImages");
        if (images == null || images.length() == 0) return null;
        JSONObject img = images.optJSONObject(0);
        if (img == null) return null;
        String base = img.optString("_sBaseUrl", "");
        String file = large ? img.optString("_sFile530", "") : img.optString("_sFile220", "");
        if (file.isEmpty()) file = img.optString("_sFile100", "");
        if (file.isEmpty()) file = img.optString("_sFile", "");
        return base.isEmpty() || file.isEmpty() ? null : base + "/" + file;
    }

    @SuppressWarnings("deprecation")
    private static String htmlToText(String html) {
        if (html == null || html.isEmpty()) return "";
        String text = android.os.Build.VERSION.SDK_INT >= 24
                ? Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                : Html.fromHtml(html).toString();
        text = text.replace('￼', ' ').trim(); // drop image placeholders
        return text.length() > 1500 ? text.substring(0, 1500) + "…" : text;
    }

    // ---- HTTP ---------------------------------------------------------------------------------

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            try (InputStream s = in) {
                while ((n = s.read(buf)) != -1) out.write(buf, 0, n);
            }
            String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (code >= 400 && !body.contains("_sErrorCode")) throw new IOException("HTTP " + code);
            return body;
        } finally {
            c.disconnect();
        }
    }

    private GameBananaApi() { }
}
