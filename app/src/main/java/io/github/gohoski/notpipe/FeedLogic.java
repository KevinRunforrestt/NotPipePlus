package io.github.gohoski.notpipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONException;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.data.VideoInfo;

/**
 * Pure logic of the NewPipe-style subscriptions feed (no I/O of its own,
 * FeedManager owns the files):
 *  - cache serialization/deserialization of the merged feed;
 *  - per-channel lastUpdate state serialization;
 *  - the outdated-subscriptions selection (NewPipe's feed_update_threshold
 *    logic: never-fetched channels first, then failed ones, then stale ones);
 *  - the merge (NewPipe's FeedDatabaseManager.getUpdatedVideos equivalent):
 *    cached videos of untouched channels + fresh ones, deduplicated by id,
 *    newest first, with channel name/avatar enrichment from the
 *    subscription list.
 *
 * Java 1.5 (Android 1.5+): no streams, no diamond operators.
 */
public final class FeedLogic {

    /** Videos kept per channel in the merged feed (NewPipe caps its feed
     *  rows per subscription too). */
    public static final int FEED_VIDEOS_PER_CHANNEL = 12;
    /** Hard cap of the merged feed: a 2011 device must not re-layout a
     *  500-row list, and the cache file stays small. */
    public static final int FEED_MAX_ITEMS = 240;

    /** Cached feed payload: the merged list + when it was loaded. */
    public static final class CachedFeed {
        public final List<VideoInfo> videos;
        public final long loadedAt;

        public CachedFeed(List<VideoInfo> videos, long loadedAt) {
            this.videos = videos;
            this.loadedAt = loadedAt;
        }
    }

    private FeedLogic() {}

    // ------------------------------------------------------------------
    // Cache (de)serialization
    // ------------------------------------------------------------------

    /** Parses the on-disk cache; null when the payload is unusable. */
    public static CachedFeed parseCache(String json) {
        if (json == null || json.length() == 0) return null;
        try {
            JSONObject root = new JSONObject(json);
            long loadedAt = root.getLong("loadedAt", 0L);
            JSONArray arr = root.getArray("videos");
            List<VideoInfo> videos = new ArrayList<VideoInfo>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JSONObject v = arr.getObject(i);
                    Date published = null;
                    long publishedAt = v.getLong("publishedAt", 0L);
                    if (publishedAt > 0) published = new Date(publishedAt);
                    videos.add(new VideoInfo(
                            v.getString("id"),
                            v.getString("title"),
                            v.getString("thumbnail"),
                            v.getString("channel"),
                            v.getString("channelThumbnail"),
                            v.getString("channelId"),
                            v.getString("duration"),
                            v.getLong("views", -1L),
                            published));
                } catch (JSONException ignored) {
                    // Skip a broken row, keep the rest.
                }
            }
            if (videos.isEmpty()) return null;
            return new CachedFeed(videos, loadedAt);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Serializes the merged feed for the on-disk cache. */
    public static String buildCacheJson(List<VideoInfo> videos, long loadedAt) {
        JSONObject root = new JSONObject();
        root.put("loadedAt", Long.valueOf(loadedAt));
        JSONArray arr = new JSONArray(Math.min(videos.size(), FEED_MAX_ITEMS));
        for (int i = 0; i < videos.size() && i < FEED_MAX_ITEMS; i++) {
            VideoInfo v = videos.get(i);
            JSONObject o = new JSONObject();
            o.put("id", v.id);
            o.put("title", v.title);
            o.put("thumbnail", v.thumbnail == null ? "" : v.thumbnail);
            o.put("channel", v.channel == null ? "" : v.channel);
            o.put("channelThumbnail", v.channelThumbnail == null ? "" : v.channelThumbnail);
            o.put("channelId", v.channelId == null ? "" : v.channelId);
            o.put("duration", v.duration == null ? "" : v.duration);
            o.put("views", Long.valueOf(v.views));
            o.put("publishedAt", Long.valueOf(v.publishedAt == null ? 0L : v.publishedAt.getTime()));
            arr.put(arr.size(), o);
        }
        root.put("videos", arr);
        return root.toString();
    }

    // ------------------------------------------------------------------
    // Per-channel state (de)serialization
    // ------------------------------------------------------------------

    /** Parses {channelId: lastUpdateMs}; empty map when unusable. */
    public static Map<String, Long> parseState(String json) {
        Map<String, Long> state = new HashMap<String, Long>();
        if (json == null || json.length() == 0) return state;
        try {
            JSONObject root = new JSONObject(json);
            java.util.Enumeration keys = root.keys();
            while (keys.hasMoreElements()) {
                String key = (String) keys.nextElement();
                try {
                    state.put(key, Long.valueOf(root.getLong(key)));
                } catch (JSONException ignored) {
                    // Skip a broken row, keep the rest.
                }
            }
        } catch (Throwable ignored) {
        }
        return state;
    }

    /** Serializes {channelId: lastUpdateMs}. */
    public static String buildStateJson(Map<String, Long> state) {
        JSONObject root = new JSONObject();
        java.util.Iterator it = state.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            Long value = (Long) entry.getValue();
            root.put((String) entry.getKey(), value == null ? Long.valueOf(0L) : value);
        }
        return root.toString();
    }

    // ------------------------------------------------------------------
    // Outdated selection + merge
    // ------------------------------------------------------------------

    /**
     * Channels worth re-checking now.
     *
     * @param subs subscription entries {id, name, avatar}
     * @param state per-channel lastUpdate timestamps
     * @param thresholdMs max age of a fresh check; -1 = check everything
     * @param now current wall-clock time
     */
    public static List<String[]> outdatedSubscriptions(List<String[]> subs,
            Map<String, Long> state, long thresholdMs, long now) {
        List<String[]> outdated = new ArrayList<String[]>();
        if (subs == null) return outdated;
        for (int i = 0; i < subs.size(); i++) {
            String[] sub = subs.get(i);
            if (sub == null || sub[0] == null || sub[0].length() == 0) continue;
            Long lastUpdate = state.get(sub[0]);
            if (thresholdMs < 0 || lastUpdate == null
                    || now - lastUpdate.longValue() > thresholdMs) {
                outdated.add(sub);
            }
        }
        return outdated;
    }

    /**
     * Merges the fresh channel results into the previous feed: videos of
     * channels that were just fetched replace their cached counterparts,
     * untouched channels keep theirs, everything is deduplicated by id,
     * sorted newest first and capped.
     */
    public static List<VideoInfo> mergeFeed(List<VideoInfo> currentFeed,
            Map<String, List<VideoInfo>> fetched, Map<String, String[]> subsById) {
        List<VideoInfo> merged = new ArrayList<VideoInfo>();
        java.util.HashSet seen = new java.util.HashSet();

        // 1. Fresh results first (they win over any cached duplicate).
        if (fetched != null) {
            java.util.Iterator it = fetched.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry entry = (Map.Entry) it.next();
                String channelId = (String) entry.getKey();
                List<VideoInfo> fresh = (List<VideoInfo>) entry.getValue();
                if (fresh == null) continue;
                for (int i = 0; i < fresh.size() && i < FEED_VIDEOS_PER_CHANNEL; i++) {
                    VideoInfo v = fresh.get(i);
                    if (v == null || v.id == null || seen.contains(v.id)) continue;
                    seen.add(v.id);
                    merged.add(enrich(v, channelId, subsById));
                }
            }
        }

        // 2. Cached videos of channels that were NOT fetched this round.
        if (currentFeed != null) {
            for (int i = 0; i < currentFeed.size(); i++) {
                VideoInfo v = currentFeed.get(i);
                if (v == null || v.id == null || seen.contains(v.id)) continue;
                if (fetched != null && v.channelId != null && fetched.containsKey(v.channelId)) {
                    continue; // replaced by fresh results above
                }
                seen.add(v.id);
                merged.add(v);
            }
        }

        // 3. Newest first; undated items sink to the bottom in stable order.
        try {
            Collections.sort(merged, new Comparator<VideoInfo>() {
                public int compare(VideoInfo a, VideoInfo b) {
                    long ta = a.publishedAt == null ? 0L : a.publishedAt.getTime();
                    long tb = b.publishedAt == null ? 0L : b.publishedAt.getTime();
                    if (ta == tb) return 0;
                    return ta > tb ? -1 : 1;
                }
            });
        } catch (Throwable ignored) {
            // Sorting must never break a refresh on an old runtime.
        }

        if (merged.size() > FEED_MAX_ITEMS) {
            return new ArrayList<VideoInfo>(merged.subList(0, FEED_MAX_ITEMS));
        }
        return merged;
    }

    /** Fills the channel name/avatar from the subscription entry when the
     *  frontend row did not carry them (RSS rows never carry an avatar). */
    private static VideoInfo enrich(VideoInfo v, String channelId,
            Map<String, String[]> subsById) {
        String[] sub = subsById == null ? null : (String[]) subsById.get(channelId);
        if (sub == null) return v;
        String name = sub.length > 1 ? sub[1] : null;
        String avatar = sub.length > 2 ? sub[2] : null;
        boolean enrichName = (v.channel == null || v.channel.length() == 0) && name != null;
        boolean enrichAvatar = (v.channelThumbnail == null || v.channelThumbnail.length() == 0) && avatar != null;
        if (!enrichName && !enrichAvatar) return v;
        return new VideoInfo(v.id, v.title, v.thumbnail,
                enrichName ? name : v.channel,
                enrichAvatar ? avatar : v.channelThumbnail,
                v.channelId, v.duration, v.views, v.publishedAt);
    }

    /** Display name of a subscription entry. */
    public static String channelName(String[] sub) {
        if (sub == null) return "";
        if (sub.length > 1 && sub[1] != null && sub[1].length() > 0) return sub[1];
        return sub[0] == null ? "" : sub[0];
    }
}
