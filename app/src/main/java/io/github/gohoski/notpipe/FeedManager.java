package io.github.gohoski.notpipe;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.api.ChannelApi;
import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.api.RssFeed;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/** NewPipe-style subscriptions feed engine, process-wide (rotation-safe). */
public final class FeedManager {

    /** Global time budget for one feed refresh. */
    public static final long FEED_TIME_BUDGET_MS = 30000L;
    /** Background workers fetching channels concurrently (dual-core A9). */
    public static final int FEED_WORKERS = 3;
    /** Distinct Invidious instances tried per channel on the RSS route. */
    public static final int FEED_RSS_ATTEMPTS = 2;
    /** A channel fetched less than this long ago is not re-checked on automatic refreshes. */
    public static final long FEED_CHANNEL_STALE_MS = 20L * 60L * 1000L;
    /** A failed channel is retried once this much time has passed. */
    public static final long FEED_RETRY_AFTER_FAIL_MS = 5L * 60L * 1000L;
    /** Shorts ids remembered per channel from the shelf/playlist lookup. */
    private static final int MAX_SHORTS_IDS = 120;
    /** Timeout of one Shorts shelf/playlist lookup (kept short to protect the 30 s refresh budget). */
    private static final int SHORTS_LOOKUP_TIMEOUT = 6000;
    /** Timeout of the newest-videos JSON listing (the Shorts-aware primary source of a channel fetch). */
    private static final int VIDEOS_JSON_TIMEOUT = 8000;
    /** Rows parsed from the newest-videos JSON (FEED_VIDEOS_PER_CHANNEL per channel). */
    private static final int VIDEOS_JSON_ROWS = 24;

    private static final String CACHE_FILE = "feed_cache.json";
    private static final String STATE_FILE = "feed_state.json";
    /** Shorts-id memory: {channelId: [videoId, ...]} of every shelf lookup that answered. */
    private static final String SHORTS_FILE = "feed_shorts.json";
    private static final int MAX_STATE_BYTES = 1024 * 1024;
    private static final Object IO_LOCK = new Object();

    // --- Process-wide update coordinator (rotation-safe) ---

    /** Refresh reasons (mirror MainActivity's constants). */
    public static final int REASON_AUTO = 0;
    public static final int REASON_MANUAL = 1;
    public static final int REASON_REBUILD = 2;
    /** After the network came back: full re-check, silent in the UI. */
    public static final int REASON_RECONNECT = 3;

    /** UI callbacks; every method may be called from the update thread. */
    public interface FeedObserver {
        void onFeedProgress(int current, int total);
        void onFeedDone(FeedUpdate result, int reason);
    }

    private static final Object UPDATE_LOCK = new Object();
    private static Thread updateThread;
    /** Cancel flag of the CURRENT run; each started run gets its own. */
    private static volatile AtomicBoolean currentRunCancel = new AtomicBoolean(false);
    private static volatile int progressCurrent;
    private static volatile int progressTotal;
    private static FeedUpdate pendingResult;
    private static int pendingReason;
    /** Monotonic id of the started runs (also stored on the result). */
    private static long updateSeqCounter;
    private static final List<FeedObserver> observers = new ArrayList<FeedObserver>();

    /** True while a refresh is running (any activity instance may ask). */
    public static boolean isUpdating() {
        synchronized (UPDATE_LOCK) {
            return updateThread != null && updateThread.isAlive();
        }
    }

    public static int getProgressCurrent() {
        return progressCurrent;
    }

    public static int getProgressTotal() {
        return progressTotal;
    }

    public static void addObserver(FeedObserver o) {
        if (o == null) return;
        synchronized (observers) {
            if (!observers.contains(o)) observers.add(o);
        }
    }

    public static void removeObserver(FeedObserver o) {
        if (o == null) return;
        synchronized (observers) {
            observers.remove(o);
        }
    }

    /** Consumes the result of a refresh that finished while no activity was attached (e.g. mid-rotation) */
    public static FeedUpdate takePendingResult() {
        synchronized (UPDATE_LOCK) {
            FeedUpdate r = pendingResult;
            pendingResult = null;
            return r;
        }
    }

    public static int takePendingReason() {
        synchronized (UPDATE_LOCK) {
            int r = pendingReason;
            pendingReason = REASON_AUTO;
            return r;
        }
    }

    /** Parks the newest result only (a stale superseded one must never overwrite it). */
    private static void parkResult(FeedUpdate result, int reason) {
        synchronized (UPDATE_LOCK) {
            if (pendingResult != null && pendingResult.seq >= result.seq) return;
            pendingResult = result;
            pendingReason = reason;
        }
    }

    /** Drops a parked result after a live observer applied it. */
    public static void clearPendingResult() {
        synchronized (UPDATE_LOCK) {
            pendingResult = null;
            pendingReason = REASON_AUTO;
        }
    }

    /** Starts a refresh at process scope. */
    public static boolean startUpdate(final Config config, final List<String[]> subs,
            final List<VideoInfo> currentFeed, final boolean ignoreThreshold, final int reason) {
        synchronized (UPDATE_LOCK) {
            if (updateThread != null && updateThread.isAlive()) {
                if (reason != REASON_REBUILD) return false;
                // A rebuild supersedes whatever is running: flag the OLD run's own flag.
                currentRunCancel.set(true);
                try {
                    updateThread.join(800);
                } catch (InterruptedException ignored) {
                }
            }
            final AtomicBoolean myCancel = new AtomicBoolean(false);
            currentRunCancel = myCancel;
            progressCurrent = 0;
            progressTotal = 0;
            final long mySeq = ++updateSeqCounter;
            updateThread = new Thread(new Runnable() {
                public void run() {
                    FeedUpdate result = null;
                    try {
                        result = updateFeed(config, subs, currentFeed, ignoreThreshold,
                                new ProgressListener() {
                                    public void onFeedProgress(int current, int total) {
                                        progressCurrent = current;
                                        progressTotal = total;
                                        notifyObserversProgress(current, total);
                                    }
                                }, myCancel);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    if (myCancel.get()) {
                        // Superseded run: still deliver a result so a UI that re-attached never waits for a notification.
                        FeedUpdate cancelledResult = new FeedUpdate();
                        cancelledResult.seq = mySeq;
                        cancelledResult.reason = reason;
                        cancelledResult.cancelled = true;
                        parkResult(cancelledResult, reason);
                        notifyObserversDone(cancelledResult, reason);
                        return;
                    }
                    if (result == null) result = new FeedUpdate();
                    result.seq = mySeq;
                    result.reason = reason;
                    parkResult(result, reason);
                    notifyObserversDone(result, reason);
                }
            }, "feed-update");
            updateThread.setPriority(Thread.NORM_PRIORITY - 1);
            updateThread.start();
            return true;
        }
    }

    /** Stops the in-flight refresh (subscription list changed / app exit). */
    public static void cancelUpdate() {
        currentRunCancel.set(true);
    }

    private static void notifyObserversProgress(int current, int total) {
        List<FeedObserver> toNotify;
        synchronized (observers) {
            toNotify = new ArrayList<FeedObserver>(observers);
        }
        for (int i = 0; i < toNotify.size(); i++) {
            try {
                (toNotify.get(i)).onFeedProgress(current, total);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void notifyObserversDone(FeedUpdate result, int reason) {
        List<FeedObserver> toNotify;
        synchronized (observers) {
            toNotify = new ArrayList<FeedObserver>(observers);
        }
        for (int i = 0; i < toNotify.size(); i++) {
            try {
                (toNotify.get(i)).onFeedDone(result, reason);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Progress listener of the update engine (called from worker threads). */
    public interface ProgressListener {
        /** Emitted from worker threads as channels complete. */
        void onFeedProgress(int current, int total);
    }

    /** Result of one feed refresh. */
    public static final class FeedUpdate {
        /** Merged feed: cached videos of untouched channels + fresh ones. */
        public List<VideoInfo> videos;
        /** Channels included in this refresh (0 = everything up to date). */
        public int checked;
        /** Channels that returned at least one video. */
        public int ok;
        /** Channels that errored (network / dead route). */
        public int failed;
        /** Display names of the failed channels. */
        public List<String> failedNames;
        /** Wall-clock time of this refresh. */
        public long loadedAt;
        /** Sequence number of the run that produced this (guards the UI against applying the same result twice). */
        public long seq;
        /** Why this refresh ran (REASON_*). */
        public int reason;
        /** True when the run was cancelled/superseded: the UI must keep the screen as-is and never wait for a follow-up. */
        public boolean cancelled;

        FeedUpdate() {
            videos = new ArrayList<VideoInfo>();
            failedNames = new ArrayList<String>();
            loadedAt = 0L;
        }
    }

    private FeedManager() {}

    // --- Disk cache ---

    /** Last successfully merged feed from internal storage, or null when no usable cache exists. */
    public static FeedLogic.CachedFeed loadCachedFeed() {
        synchronized (IO_LOCK) {
            try {
                String json = readInternal(CACHE_FILE);
                if (json == null || json.length() == 0) return null;
                FeedLogic.CachedFeed cached = FeedLogic.parseCache(json);
                if (cached == null || cached.videos == null || cached.videos.isEmpty()) return null;
                return cached;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static void saveCache(List<VideoInfo> videos, long loadedAt) {
        if (videos == null || videos.isEmpty()) return;
        synchronized (IO_LOCK) {
            try {
                writeInternal(CACHE_FILE, FeedLogic.buildCacheJson(videos, loadedAt));
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static Map<String, Long> loadChannelState() {
        synchronized (IO_LOCK) {
            try {
                return FeedLogic.parseState(readInternal(STATE_FILE));
            } catch (Throwable ignored) {
                return new HashMap<String, Long>();
            }
        }
    }

    private static void saveChannelState(Map<String, Long> state) {
        if (state == null) return;
        synchronized (IO_LOCK) {
            try {
                writeInternal(STATE_FILE, FeedLogic.buildStateJson(state));
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    // --- Update engine ---

    /** Refresh the subscriptions feed. */
    public static FeedUpdate updateFeed(Config config, List<String[]> subs,
            List<VideoInfo> currentFeed, boolean ignoreThreshold,
            ProgressListener progress, AtomicBoolean cancel) {

        final FeedUpdate r = new FeedUpdate();
        final long now = System.currentTimeMillis();
        r.loadedAt = now;
        if (subs == null || subs.isEmpty()) return r;

        final Map<String, Long> state = loadChannelState();
        final long threshold = ignoreThreshold ? -1L : FEED_CHANNEL_STALE_MS;
        final List<String[]> outdated = FeedLogic.outdatedSubscriptions(subs, state, threshold, now);
        r.checked = outdated.size();

        if (r.checked == 0) {
            // Everything was fetched recently (NewPipe: "feed is up to date").
            if (currentFeed != null && !currentFeed.isEmpty()) {
                r.videos = new ArrayList<VideoInfo>(currentFeed);
            }
            return r;
        }

        if (progress != null) progress.onFeedProgress(0, r.checked);

        final long deadline = now + FEED_TIME_BUDGET_MS;
        final RssRotation rotation = new RssRotation(config);

        Metadata api = null;
        ChannelApi channelApi = null;
        try {
            api = Manager.getInstance().getMetadata();
        } catch (Throwable ignored) {
        }
        try {
            channelApi = Manager.getInstance().getChannelApi();
        } catch (Throwable ignored) {
        }
        final Metadata fApi = api;
        final ChannelApi fChannelApi = channelApi;

        // Subscription entries by id for enrichment during the merge.
        final Map<String, String[]> subsById = new HashMap<String, String[]>();
        for (int i = 0; i < subs.size(); i++) {
            String[] s = subs.get(i);
            if (s != null && s[0] != null) subsById.put(s[0], s);
        }

        if (!Utils.hasConnection(NotPipe.getAppContext())) {
            // Offline: fail fast; ConnectivityMonitor re-triggers the visible screen on reconnect (REASON_RECONNECT).
            for (int i = 0; i < outdated.size(); i++) {
                String[] sub = (String[]) outdated.get(i);
                r.failedNames.add(FeedLogic.channelName(sub));
                if (sub != null && sub[0] != null) {
                    state.put(sub[0], Long.valueOf(
                            now - (FEED_CHANNEL_STALE_MS - FEED_RETRY_AFTER_FAIL_MS)));
                }
            }
            r.failed = outdated.size();
            r.videos = FeedLogic.mergeFeed(currentFeed, null, subsById);
            saveChannelState(state);
            return r;
        }

        final Map<String, List<VideoInfo>> fetched =
                Collections.synchronizedMap(new HashMap<String, List<VideoInfo>>());
        final List<String> failedNames = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger cursor = new AtomicInteger(0);
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger okCount = new AtomicInteger(0);
        final int total = r.checked;
        // Must be declared before the local Worker class below (Java 1.5: local classes can only capture final locals).
        final ProgressListener progressListener = progress;
        final AtomicBoolean cancelFlag = cancel;
        // Shorts hiding: the RSS rows carry no duration, so the threshold filter alone can never act on them.
        final boolean hideShortsSubs = config != null && config.isHideShortsSubscriptions();
        final int shortThreshold = config != null ? config.getShortLengthSeconds() : 0;
        // Shorts-id memory: loaded once per refresh, updated by every successful shelf lookup, applied to the merged feed and saved.
        final Map<String, HashSet> shortsIds = hideShortsSubs ? loadShortsIds() : null;
        final boolean[] shortsIdsDirty = new boolean[1];

        final class Worker implements Runnable {
            public void run() {
                while (cancelFlag == null || !cancelFlag.get()) {
                    final int idx = cursor.getAndIncrement();
                    if (idx >= total) return;
                    final String[] sub = (String[]) outdated.get(idx);
                    final String channelId = sub[0];

                    if (System.currentTimeMillis() > deadline) {
                        // Budget spent: leave the channel outdated (it goes first next refresh, like NewPipe does).
                        progress(done.incrementAndGet());
                        continue;
                    }

                    ChannelFetch fetch = null;
                    try {
                        fetch = fetchChannelVideos(rotation, fApi, fChannelApi, channelId,
                                deadline, hideShortsSubs, shortThreshold);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                    if (cancelFlag != null && cancelFlag.get()) return;

                    List<VideoInfo> vids = fetch != null ? fetch.videos : null;

                    // Remove the channel's Shorts (by id, from its Shorts shelf) before the rows enter the merged feed.
                    if (hideShortsSubs && vids != null && !vids.isEmpty()
                            && !(fetch != null && fetch.durationsFiltered)) {
                        String shelfId = fetch != null && fetch.resolvedId != null
                                ? fetch.resolvedId : channelId;
                        vids = removeShorts(vids, rotation, shelfId,
                                fetch != null ? fetch.servedBy : null, deadline,
                                shortsIds, shortsIdsDirty);
                    }

                    // RSS rows carry no channel avatar: fill them from the stored subscription entry.
                    if (vids != null && !vids.isEmpty()) {
                        vids = withAvatar(vids, Subscriptions.getAvatar(channelId));
                    }

                    if (vids != null) {
                        // Route answered: store the list even when empty (an all-Shorts channel must not resurrect cached rows).
                        synchronized (state) {
                            state.put(channelId, Long.valueOf(System.currentTimeMillis()));
                        }
                        String storeKey = fetch != null && fetch.resolvedId != null
                                ? fetch.resolvedId : channelId;
                        fetched.put(storeKey, vids);
                        if (!vids.isEmpty()) {
                            okCount.incrementAndGet();
                            // Remember the avatar seen on the channel videos.
                            for (int j = 0; j < vids.size() && j < FeedLogic.FEED_VIDEOS_PER_CHANNEL; j++) {
                                VideoInfo v = (VideoInfo) vids.get(j);
                                if (v.channelThumbnail != null && v.channelThumbnail.length() > 0) {
                                    Subscriptions.setAvatar(channelId, v.channelThumbnail);
                                    break;
                                }
                            }
                        }
                    } else {
                        failedNames.add(FeedLogic.channelName(sub));
                        // NewPipe markAsOutdated: failed channels become outdated again soon.
                        synchronized (state) {
                            state.put(channelId, Long.valueOf(
                                    System.currentTimeMillis()
                                            - (FEED_CHANNEL_STALE_MS - FEED_RETRY_AFTER_FAIL_MS)));
                        }
                    }
                    progress(done.incrementAndGet());
                }
            }

            private void progress(int current) {
                if (progressListener != null) progressListener.onFeedProgress(current, total);
            }
        }

        int workers = Math.min(FEED_WORKERS, total);
        Thread[] threads = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            threads[w] = new Thread(new Worker(), "feed-worker-" + w);
            threads[w].setPriority(Thread.NORM_PRIORITY - 1);
            threads[w].start();
        }
        for (int w = 0; w < workers; w++) {
            try {
                threads[w].join();
            } catch (InterruptedException e) {
                // Cancelled mid-refresh: tell the workers to stop.
                if (cancelFlag != null) cancelFlag.set(true);
                Thread.currentThread().interrupt();
            }
        }

        r.videos = FeedLogic.mergeFeed(currentFeed, fetched, subsById);
        if (hideShortsSubs) {
            // The merged feed (and with it the on-disk cache) must be Shorts-free.
            r.videos = FeedParse.filterByShortsIds(r.videos, shortsIds);
            r.videos = FeedParse.filterByDuration(r.videos, shortThreshold);
        }
        r.ok = okCount.get();
        r.failed = failedNames.size();
        r.failedNames = new ArrayList<String>(failedNames);
        if (r.ok > 0) {
            saveCache(r.videos, r.loadedAt);
        }
        // State advances even when no channel returned videos: channels that were legitimately checked must not be retried immediately.
        saveChannelState(state);
        if (hideShortsSubs && shortsIds != null && shortsIdsDirty[0]) {
            synchronized (IO_LOCK) {
                try {
                    writeInternal(SHORTS_FILE, buildShortsIdsJson(shortsIds));
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
        return r;
    }

    // --- Per-channel fetch (Shorts-aware primary + RSS rotation + fallbacks) ---

    /** Result of one channel fetch: the rows plus what produced them. */
    static final class ChannelFetch {
        /** Rows of the channel; null = the channel could not be fetched, a list (possibly empty) = authoritative answer. */
        List<VideoInfo> videos;
        /** Instance that produced the rows (null when unknown) - the cheapest place to ask for the Shorts shelf. */
        String servedBy;
        /** True when the rows came from the newest-videos JSON listing: Shorts excluded at the source and duration-filtered. */
        boolean durationsFiltered;
        /** Resolved UC id of a @handle subscription (null otherwise). */
        String resolvedId;
    }

    /** Fetch one channel's videos, resolving @handle subscriptions through the full channel endpoint. */
    static ChannelFetch fetchChannelVideos(RssRotation rotation, Metadata api,
            ChannelApi channelApi, String channelId, long deadline,
            boolean hideShorts, int shortThreshold) {
        if (channelId == null || channelId.length() == 0) return null;
        ChannelFetch result = new ChannelFetch();
        if (channelId.startsWith("@") && api != null) {
            try {
                Channel ch = api.getChannel(channelId);
                if (ch != null && ch.videos != null && !ch.videos.isEmpty()) {
                    Subscriptions.setResolved(channelId, ch.id, ch.title, ch.thumbnail);
                    result.videos = ch.videos;
                    result.resolvedId = ch.id;
                    return result;
                }
            } catch (Exception ignored) {
            }
        }
        // Primary with "hide Shorts": newest-videos JSON listing (Shorts-free, durations included).
        if (hideShorts && !channelId.startsWith("@")) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (System.currentTimeMillis() > deadline) break;
                String base = rotation.next();
                if (base == null) break;
                try {
                    String body = HttpClient.executeToString(
                            new HttpRequest(base + "/api/v1/channels/" + channelId + "/videos"),
                            VIDEOS_JSON_TIMEOUT);
                    List<VideoInfo> vids = FeedParse.parseChannelVideosJson(
                            body, channelId, base, shortThreshold, VIDEOS_JSON_ROWS);
                    if (vids != null && !vids.isEmpty()) {
                        result.videos = vids;
                        result.servedBy = base;
                        result.durationsFiltered = true;
                        return result;
                    }
                    // Error JSON or an empty shell: this instance's route is unusable - the next round-robin pick gets a chance.
                } catch (Throwable t) {
                    rotation.markDead(base);
                }
            }
        }
        // --- Primary without "hide Shorts": per-channel RSS through an Invidious instance ---
        List<VideoInfo> rssFallback = null;
        if (!channelId.startsWith("@")) {
            for (int attempt = 0; attempt < FEED_RSS_ATTEMPTS; attempt++) {
                if (System.currentTimeMillis() > deadline) break;
                String base = rotation.next();
                if (base == null) break; // all instances failed already
                try {
                    List<VideoInfo> rss = RssFeed.fetch(base, channelId);
                    if (rss != null) {
                        // Only a feed WITH view counts wins outright: feed rows show views + date only when both exist.
                        if (hasFeedViews(rss)) {
                            result.videos = rss;
                            result.servedBy = base;
                            return result;
                        }
                        if (rssFallback == null) rssFallback = rss;
                    }
                    rotation.markDead(base);
                } catch (Throwable t) {
                    rotation.markDead(base);
                }
            }
        }
        // ---- Fallback: the classic JSON/HTML endpoints ---- The 30 s budget must bound the fallbacks too.
        if (System.currentTimeMillis() > deadline) {
            result.videos = rssFallback;
            return result;
        }
        List<VideoInfo> channelVideos = null;
        if (api != null) {
            try {
                channelVideos = api.getChannelVideos(channelId, 0);
            } catch (Exception ignored) {
            }
        }
        if (System.currentTimeMillis() > deadline) {
            result.videos = channelVideos;
            return result;
        }
        if ((channelVideos == null || channelVideos.isEmpty()) && channelApi != null) {
            try {
                channelVideos = channelApi.getChannelVideos(channelId, 0);
            } catch (Exception ignored) {
            }
        }
        // Some instances cannot parse the channel page and fall back to the "uploads" playlist.
        if (System.currentTimeMillis() > deadline) {
            result.videos = channelVideos != null ? channelVideos : rssFallback;
            return result;
        }
        if (channelVideos != null && !channelVideos.isEmpty()
                && channelApi != null && !hasAnyFeedMetadata(channelVideos)) {
            try {
                List<VideoInfo> alt = channelApi.getChannelVideos(channelId, 0);
                if (alt != null && !alt.isEmpty() && hasAnyFeedMetadata(alt)) {
                    channelVideos = alt;
                }
            } catch (Exception ignored) {
            }
        }
        result.videos = channelVideos != null ? channelVideos : rssFallback;
        return result;
    }

    /** True when at least one video of the list has views or a date. */
    static boolean hasAnyFeedMetadata(List<VideoInfo> videos) {
        for (int i = 0; i < videos.size(); i++) {
            VideoInfo v = (VideoInfo) videos.get(i);
            if ((v.views > 0 && v.views != -1) || (v.publishedAt != null && v.publishedAt.getTime() > 0)) {
                return true;
            }
        }
        return false;
    }

    /** True when at least one video carries a view count. */
    static boolean hasFeedViews(List<VideoInfo> videos) {
        for (int i = 0; i < videos.size(); i++) {
            if (((VideoInfo) videos.get(i)).views > 0) return true;
        }
        return false;
    }

    // --- Shorts detection for the local subscriptions feed ---

    /** Removes a channel's Shorts from its feed rows. */
    static List<VideoInfo> removeShorts(List<VideoInfo> vids,
            RssRotation rotation, String channelId, String preferredBase,
            long deadline, Map<String, HashSet> shortsIds, boolean[] dirty) {
        if (vids == null || vids.isEmpty()) return vids;
        HashSet shorts = fetchShortsIds(rotation, channelId, preferredBase, deadline);
        if (shortsIds != null && channelId != null && shorts != null) {
            HashSet prev = (HashSet) shortsIds.put(channelId, shorts);
            if (dirty != null && (prev == null || !prev.equals(shorts))) dirty[0] = true;
        }
        if (shorts == null || shorts.isEmpty()) return vids;
        List<VideoInfo> out = new ArrayList<VideoInfo>(vids.size());
        for (int i = 0; i < vids.size(); i++) {
            VideoInfo v = vids.get(i);
            if (v != null && v.id != null && shorts.contains(v.id)) continue;
            out.add(v);
        }
        return out;
    }

    /** Ids of a channel's Shorts from an Invidious instance (shelf, then UUSH playlist). */
    static HashSet fetchShortsIds(RssRotation rotation, String channelId,
            String preferredBase, long deadline) {
        if (rotation == null || channelId == null) return null;
        channelId = channelId.trim();
        if (!channelId.startsWith("UC") || channelId.length() < 3) return null;

        // 1) The instance that just served the channel.
        if (preferredBase != null && System.currentTimeMillis() <= deadline) {
            HashSet ids = shortsListingIds(preferredBase,
                    "/api/v1/channels/" + channelId + "/shorts", null);
            if (ids != null) return ids;
        }
        // 2) One other instance, not yet failed at connection level.
        if (System.currentTimeMillis() <= deadline) {
            String base = rotation.nextForShorts(preferredBase);
            if (base != null) {
                HashSet ids = shortsListingIds(base,
                        "/api/v1/channels/" + channelId + "/shorts", rotation);
                if (ids != null) return ids;
            }
        }
        // 3) The channel's "shorts" playlist on the preferred instance (served by instances that disabled the shelf route).
        if (preferredBase != null && System.currentTimeMillis() <= deadline) {
            HashSet ids = shortsListingIds(preferredBase,
                    "/api/v1/playlists/UUSH" + channelId.substring(2), null);
            if (ids != null) return ids;
        }
        return null;
    }

    /** Ids of one shelf/playlist listing; markDeadOnConnFail marks the instance unusable for the rest of this refresh. */
    private static HashSet shortsListingIds(String base, String endpoint,
            RssRotation markDeadOnConnFail) {
        try {
            return FeedParse.parseVideoIds(HttpClient.executeToString(
                    new HttpRequest(base + endpoint), SHORTS_LOOKUP_TIMEOUT));
        } catch (Throwable t) {
            if (markDeadOnConnFail != null) markDeadOnConnFail.markShortsDead(base);
            return null;
        }
    }

    // --- Shorts-id memory (survives restarts; bounds the cached Shorts) ---

    /** Hard cap of remembered Shorts ids (~26 KB of JSON on flash). */
    private static final int MAX_SHORTS_IDS_TOTAL = 2400;

    /** Loads {channelId: [videoId, ...]} from internal storage; a missing or broken file yields an empty map. */
    static Map<String, HashSet> loadShortsIds() {
        Map<String, HashSet> memory = new HashMap<String, HashSet>();
        synchronized (IO_LOCK) {
            try {
                String json = readInternal(SHORTS_FILE);
                if (json == null || json.length() == 0) return memory;
                JSONObject root = JSON.getObject(json);
                java.util.Enumeration keys = root.keys();
                while (keys.hasMoreElements()) {
                    String key = (String) keys.nextElement();
                    try {
                        JSONArray arr = root.getArray(key);
                        HashSet ids = new HashSet();
                        for (int i = 0; i < arr.size(); i++) {
                            String id = arr.getString(i);
                            if (id != null && id.length() == 11) ids.add(id);
                        }
                        memory.put(key, ids);
                    } catch (Throwable ignored) {
                        // Skip a broken row, keep the rest.
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return memory;
    }

    /** Serializes the Shorts-id memory, trimmed to the total-ids cap. */
    static String buildShortsIdsJson(Map<String, HashSet> memory) {
        JSONObject root = new JSONObject();
        if (memory == null || memory.isEmpty()) return root.toString();
        int total = 0;
        java.util.Iterator it = memory.entrySet().iterator();
        while (it.hasNext() && total < MAX_SHORTS_IDS_TOTAL) {
            Map.Entry entry = (Map.Entry) it.next();
            HashSet ids = (HashSet) entry.getValue();
            JSONArray arr = new JSONArray();
            if (ids != null) {
                java.util.Iterator ii = ids.iterator();
                while (ii.hasNext() && total < MAX_SHORTS_IDS_TOTAL) {
                    String id = (String) ii.next();
                    arr.put(arr.size(), id);
                    total++;
                }
            }
            root.put((String) entry.getKey(), arr);
        }
        return root.toString();
    }

    /** Copies the list, filling the channel avatar the RSS rows never carry. */
    static List<VideoInfo> withAvatar(List<VideoInfo> vids, String avatar) {
        if (vids == null || vids.isEmpty() || avatar == null || avatar.length() == 0) return vids;
        boolean needed = false;
        for (int i = 0; i < vids.size(); i++) {
            VideoInfo v = vids.get(i);
            if (v.channelThumbnail == null || v.channelThumbnail.length() == 0) {
                needed = true;
                break;
            }
        }
        if (!needed) return vids;
        List<VideoInfo> out = new ArrayList<VideoInfo>(vids.size());
        for (int i = 0; i < vids.size(); i++) {
            VideoInfo v = vids.get(i);
            if (v.channelThumbnail != null && v.channelThumbnail.length() > 0) {
                out.add(v);
            } else {
                out.add(new VideoInfo(v.id, v.title, v.thumbnail, v.channel,
                        avatar, v.channelId, v.duration, v.views, v.publishedAt));
            }
        }
        return out;
    }

    /** Same, per video, from each channel's subscription record. */
    static List<VideoInfo> fillCachedAvatars(List<VideoInfo> vids) {
        if (vids == null || vids.isEmpty()) return vids;
        boolean needed = false;
        for (int i = 0; i < vids.size(); i++) {
            VideoInfo v = vids.get(i);
            if ((v.channelThumbnail == null || v.channelThumbnail.length() == 0)
                    && v.channelId != null && v.channelId.length() > 0) {
                needed = true;
                break;
            }
        }
        if (!needed) return vids;
        List<VideoInfo> out = new ArrayList<VideoInfo>(vids.size());
        for (int i = 0; i < vids.size(); i++) {
            VideoInfo v = vids.get(i);
            if (v.channelThumbnail != null && v.channelThumbnail.length() > 0
                    || v.channelId == null || v.channelId.length() == 0) {
                out.add(v);
            } else {
                out.add(new VideoInfo(v.id, v.title, v.thumbnail, v.channel,
                        Subscriptions.getAvatar(v.channelId), v.channelId,
                        v.duration, v.views, v.publishedAt));
            }
        }
        return out;
    }

    /** Invidious instances used for the RSS route, rotated round-robin; an instance that fails once is skipped. */
    static final class RssRotation {
        private final List<String> instances = new ArrayList<String>();
        private final List<String> dead = new ArrayList<String>();
        /** Instances that failed the SHORTS lookup at connection level (separate from RSS failures). */
        private final List<String> shortsDead = new ArrayList<String>();
        private int cursor = 0;

        RssRotation(Config config) {
            List<String> list = config == null ? null : config.getInvidiousInstances();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    String b = list.get(i);
                    if (b != null && b.trim().length() > 0) instances.add(b.trim());
                }
            }
        }

        /** Next not-yet-failed instance, or null when all are dead. */
        synchronized String next() {
            int size = instances.size();
            if (size == 0) return null;
            for (int t = 0; t < size; t++) {
                cursor = (cursor + 1) % size;
                String candidate = (String) instances.get(cursor);
                if (!dead.contains(candidate)) return candidate;
            }
            return null;
        }

        synchronized void markDead(String base) {
            if (base != null && !dead.contains(base)) dead.add(base);
        }

        /** Next instance not yet failed at connection level for the SHORTS lookup and different from "exclude". */
        synchronized String nextForShorts(String exclude) {
            int size = instances.size();
            if (size == 0) return null;
            for (int t = 0; t < size; t++) {
                cursor = (cursor + 1) % size;
                String candidate = (String) instances.get(cursor);
                if (shortsDead.contains(candidate)) continue;
                if (exclude != null && candidate.equals(exclude)) continue;
                return candidate;
            }
            return null;
        }

        /** An instance whose shorts lookup failed at connection level is skipped for the rest of the refresh. */
        synchronized void markShortsDead(String base) {
            if (base != null && !shortsDead.contains(base)) shortsDead.add(base);
        }
    }

    // --- Internal storage helpers ---

    private static String readInternal(String name) throws IOException {
        Context ctx = NotPipe.getAppContext();
        if (ctx == null) return null;
        FileInputStream in = ctx.openFileInput(name);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 8192);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_STATE_BYTES * 4) throw new IOException("File too large");
            }
            reader.close();
            return sb.toString();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeInternal(String name, String content) throws IOException {
        Context ctx = NotPipe.getAppContext();
        if (ctx == null) return;
        FileOutputStream out = ctx.openFileOutput(name, 0);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }
}
