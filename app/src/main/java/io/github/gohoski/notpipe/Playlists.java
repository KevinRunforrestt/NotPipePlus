package io.github.gohoski.notpipe;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONException;
import cc.nnproject.json.JSONObject;

/**
 * Local playlists store: one small JSON file of {name, videos[]} entries.
 * A video is stored as its id only; the Playlists screen resolves titles
 * and thumbnails through the API on demand. Java 1.5 compatible.
 */
public final class Playlists {

    private static final String FILE = "playlists.json";
    private static final Object LOCK = new Object();
    private static List<Object[]> playlists = null; // {String name, List<String> ids}

    private Playlists() {}

    /** Snapshot of the playlist names, in creation order. */
    public static List<String> getNames() {
        List<String> names = new ArrayList<String>();
        synchronized (LOCK) {
            loadIfNeeded();
            for (int i = 0; i < playlists.size(); i++) {
                names.add((String) ((Object[]) playlists.get(i))[0]);
            }
        }
        return names;
    }

    /** Ids of the videos of one playlist (empty when it does not exist). */
    public static List<String> getVideos(String name) {
        List<String> ids = new ArrayList<String>();
        if (name == null) return ids;
        synchronized (LOCK) {
            loadIfNeeded();
            Object[] pl = find(name);
            if (pl != null) {
                ids.addAll((List<String>) pl[1]);
            }
        }
        return ids;
    }

    /** True when the playlist exists and already contains the video. */
    public static boolean contains(String name, String videoId) {
        if (name == null || videoId == null) return false;
        synchronized (LOCK) {
            loadIfNeeded();
            Object[] pl = find(name);
            if (pl == null) return false;
            return ((List<String>) pl[1]).contains(videoId);
        }
    }

    /** Creates a playlist; false when the name already exists. */
    public static boolean create(String name) {
        if (name == null || name.trim().length() == 0) return false;
        synchronized (LOCK) {
            loadIfNeeded();
            if (find(name.trim()) != null) return false;
            List<String> ids = new ArrayList<String>();
            playlists.add(new Object[]{ name.trim(), ids });
            save();
        }
        return true;
    }

    /** Appends a video to a playlist (created when missing). */
    public static boolean add(String name, String videoId) {
        if (name == null || name.trim().length() == 0 || videoId == null) return false;
        name = name.trim();
        synchronized (LOCK) {
            loadIfNeeded();
            Object[] pl = find(name);
            if (pl == null) {
                List<String> ids = new ArrayList<String>();
                playlists.add(new Object[]{ name, ids });
                pl = find(name);
            }
            List<String> ids = (List<String>) pl[1];
            if (ids.contains(videoId)) return true;
            ids.add(videoId);
            save();
        }
        return true;
    }

    /** Deletes a whole playlist. */
    public static void delete(String name) {
        if (name == null) return;
        synchronized (LOCK) {
            loadIfNeeded();
            for (int i = 0; i < playlists.size(); i++) {
                if (name.equals(((Object[]) playlists.get(i))[0])) {
                    playlists.remove(i);
                    save();
                    return;
                }
            }
        }
    }

    private static Object[] find(String name) {
        for (int i = 0; i < playlists.size(); i++) {
            Object[] pl = (Object[]) playlists.get(i);
            if (name.equals(pl[0])) return pl;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private static void loadIfNeeded() {
        if (playlists != null) return;
        playlists = new ArrayList<Object[]>();
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx == null) return;
            String json = readInternal(ctx);
            if (json == null || json.length() == 0) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JSONObject o = arr.getObject(i);
                    String name = o.getString("name");
                    if (name == null || name.length() == 0) continue;
                    JSONArray vids = o.getArray("videos");
                    List<String> ids = new ArrayList<String>(vids.size());
                    for (int j = 0; j < vids.size(); j++) {
                        String id = vids.getString(j);
                        if (id != null && id.length() > 0) ids.add(id);
                    }
                    playlists.add(new Object[]{ name, ids });
                } catch (JSONException ignored) {
                    // Skip a broken row, keep the rest.
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx == null) return;
            JSONArray arr = new JSONArray(playlists.size());
            for (int i = 0; i < playlists.size(); i++) {
                Object[] pl = (Object[]) playlists.get(i);
                JSONObject o = new JSONObject();
                o.put("name", (String) pl[0]);
                JSONArray ids = new JSONArray();
                List<String> videos = (List<String>) pl[1];
                for (int j = 0; j < videos.size(); j++) {
                    ids.put(ids.size(), videos.get(j));
                }
                o.put("videos", ids);
                arr.put(arr.size(), o);
            }
            writeInternal(ctx, arr.toString());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static String readInternal(Context ctx) {
        try {
            FileInputStream in = ctx.openFileInput(FILE);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 4096);
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[2048];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                    if (sb.length() > 1024 * 1024) throw new JSONException("File too large");
                }
                reader.close();
                return sb.toString();
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeInternal(Context ctx, String content) throws Exception {
        FileOutputStream out = ctx.openFileOutput(FILE, 0);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
