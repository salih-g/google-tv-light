package dev.salih.tvbrowser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Bookmarks, history, tab records and per-site settings as small JSON files in the app's private dir. */
final class Store {
    static final class Entry {
        String title, url; long time; int scroll;
        Entry(String title, String url) { this.title = title; this.url = url; this.time = System.currentTimeMillis(); }
    }

    private final File dir;
    final List<Entry> bookmarks = new ArrayList<>();
    final List<Entry> history = new ArrayList<>();   // newest first, capped
    final List<Entry> tabs = new ArrayList<>();      // records only; the live WebView is separate
    int activeTab = 0;
    private static final int HISTORY_MAX = 200;

    Store(Context ctx, String[] defaultBookmarks) {
        dir = ctx.getFilesDir();
        load("bookmarks.json", bookmarks); load("history.json", history);
        JSONObject t = readObject("tabs.json");
        if (t != null) { fill(t.optJSONArray("tabs"), tabs); activeTab = t.optInt("active", 0); }
        if (bookmarks.isEmpty() && !new File(dir, "bookmarks.json").exists()) {
            for (String s : defaultBookmarks) { String[] p = s.split("\\|", 2); if (p.length == 2) bookmarks.add(new Entry(p[0], p[1])); }
            saveBookmarks();
        }
    }

    // ---- bookmarks ----
    Entry bookmarkFor(String url) { for (Entry e : bookmarks) if (e.url.equals(url)) return e; return null; }
    void saveBookmarks() { write("bookmarks.json", toArray(bookmarks)); }

    // ---- history ----
    void visited(String title, String url) {
        if (url == null || url.startsWith("about:") || url.startsWith("data:")) return;
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).url.equals(url)) history.remove(i);
        history.add(0, new Entry(title == null || title.isEmpty() ? url : title, url));
        while (history.size() > HISTORY_MAX) history.remove(history.size() - 1);
        write("history.json", toArray(history));
    }
    void clearHistory() { history.clear(); write("history.json", new JSONArray()); }

    /** History + bookmarks whose title or url contains the typed text, newest first. */
    List<Entry> suggest(String typed, int max) {
        List<Entry> out = new ArrayList<>();
        String q = typed.toLowerCase();
        for (List<Entry> src : new List[]{history, bookmarks}) for (Entry e : src) {
            if (out.size() >= max) break;
            if (q.isEmpty() || e.url.toLowerCase().contains(q) || e.title.toLowerCase().contains(q)) {
                boolean dup = false; for (Entry o : out) if (o.url.equals(e.url)) dup = true;
                if (!dup) out.add(e);
            }
        }
        return out;
    }

    // ---- tabs ----
    void saveTabs() {
        JSONObject o = new JSONObject();
        try { o.put("tabs", toArray(tabs)); o.put("active", activeTab); } catch (JSONException ignored) { }
        write("tabs.json", o);
    }

    // ---- per-site: desktop (default) or mobile user agent ----
    boolean mobileSite(String host) { return readObject("sites.json") != null && readObject("sites.json").optBoolean(host, false); }
    void setMobileSite(String host, boolean mobile) {
        JSONObject o = readObject("sites.json"); if (o == null) o = new JSONObject();
        try { if (mobile) o.put(host, true); else o.remove(host); } catch (JSONException ignored) { }
        write("sites.json", o);
    }

    // ---- json plumbing ----
    private static JSONArray toArray(List<Entry> list) {
        JSONArray a = new JSONArray();
        for (Entry e : list) {
            JSONObject o = new JSONObject();
            try { o.put("t", e.title); o.put("u", e.url); o.put("ts", e.time); o.put("s", e.scroll); } catch (JSONException ignored) { }
            a.put(o);
        }
        return a;
    }
    private static void fill(JSONArray a, List<Entry> list) {
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i); if (o == null) continue;
            Entry e = new Entry(o.optString("t"), o.optString("u")); e.time = o.optLong("ts"); e.scroll = o.optInt("s");
            if (!e.url.isEmpty()) list.add(e);
        }
    }
    private void load(String name, List<Entry> into) {
        try { fill(new JSONArray(new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8)), into); }
        catch (IOException | JSONException ignored) { }
    }
    private JSONObject readObject(String name) {
        try { return new JSONObject(new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8)); }
        catch (IOException | JSONException e) { return null; }
    }
    private void write(String name, Object json) {
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(dir, name))) throw new IOException("rename");
        } catch (IOException ignored) { }
    }
}
