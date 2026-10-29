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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.gohoski.notpipe.api.ChannelApi;
import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.api.RssFeed;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.VideoInfo;

/**
 * NewPipe-style subscriptions feed engine, process-wide (rotation-safe).
 * Mirrors NewPipe's FeedLoadManager: per-subscription lastUpdate
 * timestamps, a "Loading channels: X / Y" progress event, 3 parallel
 * extractions, per-channel error collection and earlier retries of failed
 * channels. Platform adaptations (Android 2.3, 512 MB): plain threads and
 * small JSON files instead of Room/RxJava, the merged feed is cached to
 * internal storage so the tab paints instantly, and a 30 s wall-clock
 * budget bounds every refresh (channels that do not fit stay outdated
 * and are fetched first on the next refresh).
 */
public final class FeedManager {

    /** Global time budget for one feed refresh. */
    public static final long FEED_TIME_BUDGET_MS = 30000L;
    /** Background workers fetching channels concurrently (dual-core A9). */
    public static final int FEED_WORKERS = 3;
    /** Distinct Invidious instances tried per channel on the RSS route. */
    public static final int FEED_RSS_ATTEMPTS = 2;
    /** A channel fetched less than this long ago is not re-checked on
     *  automatic refreshes (NewPipe's feed_update_threshold). */
    public static final long FEED_CHANNEL_STALE_MS = 20L * 60L * 1000L;
    /** A failed channel is retried once this much time has passed. */
    public static final long FEED_RETRY_AFTER_FAIL_MS = 5L * 60L * 1000L;

    private static final String CACHE_FILE = "feed_cache.json";
    private static final String STATE_FILE = "feed_state.json";
    private static final int MAX_STATE_BYTES = 1024 * 1024;
    private static final Object IO_LOCK = new Object();

    // ------------------------------------------------------------------
    // Process-wide update coordinator (rotation-safe)
    // ------------------------------------------------------------------
    // The in-flight refresh lives in the singleton scope, NOT in the
    // activity: rotating the phone destroys/recreates MainActivity on
    // Android 2.3. Here the refresh keeps running through the rotation;
    // the new activity instance simply re-attaches: it reads the live
    // progress and receives the result via the observer, or picks it up
    // from pendingResult if it finished while no activity was attached.

    /** Refresh reasons (mirror MainActivity's constants). */
    public static final int REASON_AUTO = 0;
    public static final int REASON_MANUAL = 1;
    public static final int REASON_REBUILD = 2;

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

    /**
     * Consumes the result of a refresh that finished while no activity was
     * attached (e.g. mid-rotation); null when there is nothing pending.
     */
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

    /** Drops a parked result after a live observer applied it. */
    public static void clearPendingResult() {
        synchronized (UPDATE_LOCK) {
            pendingResult = null;
            pendingReason = REASON_AUTO;
        }
    }

    /**
     * Starts a refresh at process scope. No-op (returns false) when one is
     * already running; a REBUILD first stops the previous refresh.
     *
     * @return true when this call started a new refresh
     */
    public static boolean startUpdate(final Config config, final List<String[]> subs,
            final List<VideoInfo> currentFeed, final boolean ignoreThreshold, final int reason) {
        synchronized (UPDATE_LOCK) {
            if (updateThread != null && updateThread.isAlive()) {
                if (reason != REASON_REBUILD) return false;
                // A rebuild supersedes whatever is running: flag the OLD
                // run's own flag, wait briefly for the workers to wind
                // down, then start a fresh run.
                currentRunCancel.set(true);
                try {
                    updateThread.join(3000);
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
                        return; // superseded by a rebuild: result discarded
                    }
                    if (result == null) result = new FeedUpdate();
                    result.seq = mySeq;
                    result.reason = reason;
                    synchronized (UPDATE_LOCK) {
                        pendingResult = result;
                        pendingReason = reason;
                    }
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
        /**
         * Emitted from worker threads as channels complete.
         * @param current finished channels (failed ones included)
         * @param total channels included in this refresh
         */
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
        /** Sequence number of the run that produced this (guards the UI
         *  against applying the same result twice). */
        public long seq;
        /** Why this refresh ran (REASON_*). */
        public int reason;

        FeedUpdate() {
            videos = new ArrayList<VideoInfo>();
            failedNames = new ArrayList<String>();
            loadedAt = 0L;
        }
    }

    private FeedManager() {}

    // ------------------------------------------------------------------
    // Disk cache
    // ------------------------------------------------------------------

    /**
     * Last successfully merged feed from internal storage, or null when no
     * usable cache exists (reading a few dozen KB from flash is what makes
     * the feed appear instantly on app open).
     */
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

    // ------------------------------------------------------------------
    // Update engine
    // ------------------------------------------------------------------

    /**
     * Refresh the subscriptions feed.
     *
     * @param config user configuration (provides the Invidious instances
     *               used for the RSS route)
     * @param subs snapshot of the subscription list
     * @param currentFeed feed currently on screen / cached (merged with the
     *                    fresh results); null rebuilds the feed from scratch
     * @param ignoreThreshold true = check every channel (manual refresh /
     *                    subscription list changed), false = only channels
     *                    not fetched within FEED_CHANNEL_STALE_MS
     * @param progress optional progress listener (called from workers)
     * @param cancel optional cancellation flag
     * @return the refresh result, never null
     */
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

        final Map<String, List<VideoInfo>> fetched =
                Collections.synchronizedMap(new HashMap<String, List<VideoInfo>>());
        final List<String> failedNames = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger cursor = new AtomicInteger(0);
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicInteger okCount = new AtomicInteger(0);
        final int total = r.checked;
        // Must be declared before the local Worker class below (Java 1.5:
        // local classes can only capture final locals).
        final ProgressListener progressListener = progress;
        final AtomicBoolean cancelFlag = cancel;

        final class Worker implements Runnable {
            public void run() {
                while (cancelFlag == null || !cancelFlag.get()) {
                    final int idx = cursor.getAndIncrement();
                    if (idx >= total) return;
                    final String[] sub = (String[]) outdated.get(idx);
                    final String channelId = sub[0];

                    if (System.currentTimeMillis() > deadline) {
                        // Budget spent: leave the channel outdated (it goes
                        // first next refresh, like NewPipe does).
                        progress(done.incrementAndGet());
                        continue;
                    }

                    List<VideoInfo> vids = null;
                    try {
                        vids = fetchChannelVideos(rotation, fApi, fChannelApi, channelId, deadline);
                    } catch (Throwable t) {
                        t.printStackTrace();
                        vids = null;
                    }
                    if (cancelFlag != null && cancelFlag.get()) return;

                    if (vids != null) {
                        // Route answered (an empty but valid feed is a
                        // legitimate "no uploads"): mark it as checked.
                        synchronized (state) {
                            state.put(channelId, Long.valueOf(System.currentTimeMillis()));
                        }
                        if (!vids.isEmpty()) {
                            fetched.put(channelId, vids);
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
                        // NewPipe markAsOutdated: failed channels become
                        // outdated again soon.
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
        r.ok = okCount.get();
        r.failed = failedNames.size();
        r.failedNames = new ArrayList<String>(failedNames);
        if (r.ok > 0) {
            saveCache(r.videos, r.loadedAt);
        }
        // State advances even when no channel returned videos: channels
        // that were legitimately checked must not be retried immediately.
        saveChannelState(state);
        return r;
    }

    // ------------------------------------------------------------------
    // Per-channel fetch (RSS-first with instance rotation + fallbacks)
    // ------------------------------------------------------------------

    /**
     * Fetch one channel's videos, resolving @handle subscriptions through
     * the full channel endpoint (which also returns the real UC id, name
     * and avatar - persisted so it happens only once). Primary route for
     * plain UC ids is the NewPipe-style channel RSS served by the Invidious
     * instances; the classic JSON/HTML endpoints remain as fallback.
     *
     * @return null = the channel could not be fetched; a list (possibly
     *         empty) = authoritative answer
     */
    static List<VideoInfo> fetchChannelVideos(RssRotation rotation, Metadata api,
            ChannelApi channelApi, String channelId, long deadline) {
        if (channelId == null || channelId.length() == 0) return null;
        if (channelId.startsWith("@") && api != null) {
            try {
                Channel ch = api.getChannel(channelId);
                if (ch != null && ch.videos != null && !ch.videos.isEmpty()) {
                    Subscriptions.setResolved(channelId, ch.id, ch.title, ch.thumbnail);
                    return ch.videos;
                }
            } catch (Exception ignored) {
            }
        }
        // ---- Primary: per-channel RSS through an Invidious instance ----
        if (!channelId.startsWith("@")) {
            for (int attempt = 0; attempt < FEED_RSS_ATTEMPTS; attempt++) {
                if (System.currentTimeMillis() > deadline) return null;
                String base = rotation.next();
                if (base == null) break; // all instances failed already
                try {
                    List<VideoInfo> rss = RssFeed.fetch(base, channelId);
                    if (rss != null) return rss;
                    rotation.markDead(base);
                } catch (Throwable t) {
                    // Network error, bot-challenge, dead node: skip this
                    // instance for the rest of the refresh.
                    rotation.markDead(base);
                }
            }
        }
        // ---- Fallback: the classic JSON/HTML endpoints ----
        List<VideoInfo> channelVideos = null;
        if (api != null) {
            try {
                channelVideos = api.getChannelVideos(channelId, 0);
            } catch (Exception ignored) {
            }
        }
        if ((channelVideos == null || channelVideos.isEmpty()) && channelApi != null) {
            try {
                channelVideos = channelApi.getChannelVideos(channelId, 0);
            } catch (Exception ignored) {
            }
        }
        // Some instances cannot parse the channel page and fall back to the
        // "uploads" playlist, which carries NO view counts and NO dates.
        // Prefer the ChannelApi (yt2009) result when the metadata one is
        // that poor.
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
        return channelVideos;
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

    /**
     * Invidious instances used for the RSS route, rotated round-robin; an
     * instance that fails once is skipped for the rest of the refresh.
     */
    static final class RssRotation {
        private final List<String> instances = new ArrayList<String>();
        private final List<String> dead = new ArrayList<String>();
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
    }

    // ------------------------------------------------------------------
    // Internal storage helpers
    // ------------------------------------------------------------------

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
