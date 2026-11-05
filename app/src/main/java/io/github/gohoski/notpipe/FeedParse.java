package io.github.gohoski.notpipe;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONException;
import cc.nnproject.json.JSONObject;

import io.github.gohoski.notpipe.data.VideoInfo;

/** Pure parsing helpers of the local subscriptions feed: no I/O and no Android classes. */
public final class FeedParse {

    /** Ids remembered per channel from a shelf/playlist lookup. */
    public static final int MAX_SHORTS_IDS = 120;

    private FeedParse() {}

    // --- Durations ---

    /** Parses a duration string into a total number of seconds. */
    public static int parseDurationSeconds(String duration) {
        if (duration == null) return -1;
        duration = duration.trim();
        if (duration.length() == 0) return -1;
        // ISO 8601 period ("PT#H#M#S"): some frontends emit it instead of a clock string.
        if (duration.charAt(0) == 'P') return parseIsoPeriodSeconds(duration);
        String[] parts = duration.split(":");
        if (parts.length == 0) return -1;
        int total = 0;
        try {
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.length() == 0) return -1;
                int value = Integer.parseInt(part);
                int factor = 1;
                for (int j = i + 1; j < parts.length; j++) {
                    factor *= 60;
                }
                total += value * factor;
            }
            return total;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Seconds of an ISO 8601 period ("PT1M30S", "PT2H5M40S"); -1 when the string is not a well-formed time period. */
    static int parseIsoPeriodSeconds(String iso) {
        int total = 0;
        int value = -1;
        boolean inTime = false;
        boolean fraction = false;
        boolean anyUnit = false;
        for (int i = 0; i < iso.length(); i++) {
            char c = iso.charAt(i);
            if (c >= '0' && c <= '9') {
                if (fraction) continue;
                value = value < 0 ? (c - '0') : value * 10 + (c - '0');
            } else if (c == 'T') {
                inTime = true;
            } else if (c == '.') {
                fraction = true;
            } else if (c == 'H' || c == 'M' || c == 'S') {
                if (value < 0 || !inTime) return -1;
                if (c == 'H') total += value * 3600;
                else if (c == 'M') total += value * 60;
                else total += value;
                value = -1;
                fraction = false;
                anyUnit = true;
            } else if (c != 'P') {
                return -1;
            }
        }
        return anyUnit ? total : -1;
    }

    /** "H:MM:SS" / "M:SS" clock string of a length in seconds; "" for 0 (live rows carry none). */
    public static String formatDuration(int totalSeconds) {
        if (totalSeconds < 1) return "";
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + ":" + pad2(minutes) + ":" + pad2(seconds);
        }
        return minutes + ":" + pad2(seconds);
    }

    private static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** Drops the rows under the Shorts threshold from a list. */
    public static List<VideoInfo> filterByDuration(List<VideoInfo> videos, int thresholdSeconds) {
        if (videos == null || videos.isEmpty() || thresholdSeconds <= 0) return videos;
        List<VideoInfo> out = null;
        for (int i = 0; i < videos.size(); i++) {
            VideoInfo v = videos.get(i);
            boolean shortRow = false;
            if (v != null && v.duration != null && v.duration.length() > 0) {
                int len = parseDurationSeconds(v.duration);
                shortRow = len > 0 && len < thresholdSeconds;
            }
            if (shortRow) {
                if (out == null) {
                    out = new ArrayList<VideoInfo>(videos.size());
                    for (int j = 0; j < i; j++) out.add(videos.get(j));
                }
                continue; // drop
            }
            if (out != null) out.add(v);
        }
        return out != null ? out : videos;
    }

    /** Drops every remembered Short from a feed list. */
    public static List<VideoInfo> filterByShortsIds(List<VideoInfo> videos,
            Map<String, HashSet> memory) {
        if (videos == null || videos.isEmpty() || memory == null || memory.isEmpty()) {
            return videos;
        }
        HashSet all = new HashSet();
        java.util.Iterator it = memory.values().iterator();
        while (it.hasNext()) {
            HashSet ids = (HashSet) it.next();
            if (ids != null) all.addAll(ids);
        }
        if (all.isEmpty()) return videos;
        List<VideoInfo> out = new ArrayList<VideoInfo>(videos.size());
        for (int i = 0; i < videos.size(); i++) {
            VideoInfo v = videos.get(i);
            if (v != null && v.id != null && all.contains(v.id)) continue;
            out.add(v);
        }
        return out;
    }

    // --- Shorts shelf / Shorts playlist ids ---

    /** videoIds of a JSON listing with a "videos" array; null for anything else. */
    public static HashSet parseVideoIds(String json) {
        if (json == null) return null;
        String s = json.trim();
        if (s.length() == 0 || s.charAt(0) != '{') return null;
        try {
            JSONObject obj = JSON.getObject(s);
            JSONArray arr = obj.getArray("videos");
            if (arr == null) return null;
            HashSet ids = new HashSet();
            for (int i = 0; i < arr.size() && ids.size() < MAX_SHORTS_IDS; i++) {
                try {
                    String id = arr.getObject(i).getString("videoId");
                    if (id != null && id.length() == 11) ids.add(id);
                } catch (JSONException ignored) {
                    // Skip a broken row, keep the rest.
                }
            }
            return ids;
        } catch (Throwable t) {
            return null;
        }
    }

    // --- /api/v1/channels/{id}/videos rows ---

    /** Parses the newest-videos JSON of a channel into feed rows, dropping the rows under the Shorts threshold inline. */
    public static List<VideoInfo> parseChannelVideosJson(String json, String channelId,
            String base, int thresholdSeconds, int maxRows) {
        if (json == null) return null;
        String s = json.trim();
        if (s.length() == 0 || s.charAt(0) != '{') return null;
        try {
            JSONObject obj = JSON.getObject(s);
            JSONArray arr = obj.getArray("videos");
            if (arr == null) return null; // error JSON: not a listing
            List<VideoInfo> videos = new ArrayList<VideoInfo>(Math.min(arr.size(), maxRows));
            for (int i = 0; i < arr.size() && videos.size() < maxRows; i++) {
                try {
                    JSONObject j = arr.getObject(i);
                    if (!"video".equals(j.getString("type"))) continue;
                    String id = j.getString("videoId");
                    if (id == null || id.length() != 11) continue;
                    int lengthSeconds = -1;
                    try {
                        lengthSeconds = j.getInt("lengthSeconds");
                    } catch (JSONException ignored) {
                    }
                    // The Shorts filter: this endpoint leaves the Shorts out on its own, the threshold catches builds that still mix.
                    if (lengthSeconds > 0 && thresholdSeconds > 0
                            && lengthSeconds < thresholdSeconds) {
                        continue;
                    }
                    long views = -1L;
                    try {
                        views = j.getLong("viewCount");
                    } catch (JSONException ignored) {
                    }
                    Date published = null;
                    try {
                        long ms = j.getLong("published");
                        if (ms > 0) published = new Date(ms * 1000L);
                    } catch (JSONException ignored) {
                    }
                    String author = "";
                    try {
                        author = j.getString("author");
                    } catch (JSONException ignored) {
                    }
                    String b = base == null ? "" : base;
                    videos.add(new VideoInfo(id, j.getString("title"),
                            b + "/vi/" + id + "/mqdefault.jpg",
                            author, null, channelId,
                            formatDuration(lengthSeconds), views, published));
                } catch (JSONException ignored) {
                    // Skip a broken row, keep the rest.
                }
            }
            return videos;
        } catch (Throwable t) {
            return null;
        }
    }
}
