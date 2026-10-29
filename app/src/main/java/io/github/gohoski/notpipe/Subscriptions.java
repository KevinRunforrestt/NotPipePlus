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
 * Local subscription store, NewPipe-style: one small JSON file of
 * {id, name, avatar} entries, kept in memory and persisted on every
 * change. A subscription entry holds the UC channel id (or the @handle
 * until it is resolved through a channel page), the display name and the
 * best avatar URL seen so far (used to enrich feed rows and to paint the
 * Subscriptions screen).
 *
 * Thread-safe: the feed workers call setAvatar/setResolved from their own
 * threads while the UI reads the list. Java 1.5 compatible.
 */
public final class Subscriptions {

    private static final String FILE = "subscriptions.json";
    private static final Object LOCK = new Object();
    private static List<String[]> subs = null;

    private Subscriptions() {}

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Number of subscribed channels. */
    public static int count() {
        synchronized (LOCK) {
            loadIfNeeded();
            return subs.size();
        }
    }

    /** Snapshot of the subscription entries: {id, name, avatar}. */
    public static List<String[]> get() {
        synchronized (LOCK) {
            loadIfNeeded();
            return new ArrayList<String[]>(subs);
        }
    }

    public static boolean isSubscribed(String channelId) {
        if (channelId == null || channelId.length() == 0) return false;
        synchronized (LOCK) {
            loadIfNeeded();
            for (int i = 0; i < subs.size(); i++) {
                if (channelId.equals(subs.get(i)[0])) return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    public static void subscribe(String channelId, String name, String avatar) {
        if (channelId == null || channelId.length() == 0) return;
        synchronized (LOCK) {
            loadIfNeeded();
            if (indexOf(channelId) != -1) return;
            subs.add(new String[]{ channelId,
                    name == null ? "" : name,
                    avatar == null ? "" : avatar });
            save();
        }
    }

    public static void unsubscribe(String channelId) {
        if (channelId == null) return;
        synchronized (LOCK) {
            loadIfNeeded();
            int idx = indexOf(channelId);
            if (idx != -1) {
                subs.remove(idx);
                save();
            }
        }
    }

    /** Replaces an @handle entry with its resolved UC id (and real name /
     *  avatar), keeping the list position. Called by the feed workers when
     *  they resolve a handle subscription through the channel endpoint. */
    public static void setResolved(String handle, String channelId, String name, String avatar) {
        if (handle == null || channelId == null || channelId.startsWith("@")) return;
        synchronized (LOCK) {
            loadIfNeeded();
            int idx = indexOf(handle);
            if (idx == -1) return;
            // Already resolved elsewhere?  Just refresh name/avatar.
            int existing = indexOf(channelId);
            if (existing != -1 && existing != idx) {
                String[] row = subs.get(existing);
                if (name != null && name.length() > 0) row[1] = name;
                if (avatar != null && avatar.length() > 0) row[2] = avatar;
                subs.remove(idx);
                save();
                return;
            }
            String[] row = subs.get(idx);
            row[0] = channelId;
            if (name != null && name.length() > 0) row[1] = name;
            if (avatar != null && avatar.length() > 0) row[2] = avatar;
            save();
        }
    }

    /** Remembers the channel avatar seen on its videos (feed rows from RSS
     *  carry none). Best-effort: ignores empty URLs and no-ops when the
     *  channel already has one. */
    public static void setAvatar(String channelId, String avatar) {
        if (channelId == null || avatar == null || avatar.length() == 0) return;
        synchronized (LOCK) {
            loadIfNeeded();
            int idx = indexOf(channelId);
            if (idx == -1) return;
            String[] row = subs.get(idx);
            if (avatar.equals(row[2])) return;
            row[2] = avatar;
            save();
        }
    }

    /** Bulk add (import): returns how many entries were actually added. */
    public static int addAll(List<String[]> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        int added = 0;
        synchronized (LOCK) {
            loadIfNeeded();
            for (int i = 0; i < entries.size(); i++) {
                String[] e = entries.get(i);
                if (e == null || e[0] == null || e[0].length() == 0) continue;
                if (indexOf(e[0]) != -1) continue;
                subs.add(new String[]{ e[0],
                        e.length > 1 && e[1] != null ? e[1] : "",
                        e.length > 2 && e[2] != null ? e[2] : "" });
                added++;
            }
            if (added > 0) save();
        }
        return added;
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private static int indexOf(String channelId) {
        for (int i = 0; i < subs.size(); i++) {
            if (channelId.equals(subs.get(i)[0])) return i;
        }
        return -1;
    }

    private static void loadIfNeeded() {
        if (subs != null) return;
        subs = new ArrayList<String[]>();
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx == null) return;
            String json = readInternal(ctx);
            if (json == null || json.length() == 0) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JSONObject o = arr.getObject(i);
                    String id = o.getString("id");
                    if (id == null || id.length() == 0) continue;
                    subs.add(new String[]{
                            id,
                            o.getString("name", ""),
                            o.getString("avatar", "")});
                } catch (JSONException ignored) {
                    // Skip a broken row, keep the rest.
                }
            }
        } catch (Throwable ignored) {
            // Unreadable store: start empty rather than crash.
        }
    }

    private static void save() {
        try {
            final Context ctx = NotPipe.getAppContext();
            if (ctx == null) return;
            JSONArray arr = new JSONArray(subs.size());
            for (int i = 0; i < subs.size(); i++) {
                String[] s = subs.get(i);
                JSONObject o = new JSONObject();
                o.put("id", s[0]);
                o.put("name", s.length > 1 ? s[1] : "");
                o.put("avatar", s.length > 2 ? s[2] : "");
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
