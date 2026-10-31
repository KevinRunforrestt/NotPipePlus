package io.github.gohoski.notpipe.api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.github.gohoski.notpipe.NotPipe;
import io.github.gohoski.notpipe.R;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.SubtitleTrack;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;
import io.github.gohoski.notpipe.http.VideoTooLongException;
import io.github.gohoski.notpipe.util.InstanceHealth;

/**
 * Manager class for handling multiple API instances with fallback capability.
 */
public class Manager {
    private static Manager instance;
    private Random random;
    private ConfigManager configManager;

    private List<Metadata> metadataInstances;
    private List<VideoStream> videoInstances; // 360p-capable
    private List<VideoStream> hqInstances; // 480p+ capable
    private List<Conversion> conversionInstances;
    private List<ChannelApi> channelApiInstances;

    private Manager() {
        random = new Random();
    }

    public static void init() {
        if (instance == null) {
            instance = new Manager();
            instance.initializeInstances();
        }
    }

    public static Manager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Manager.init() must be called first!");
        }
        return instance;
    }

    private void initializeInstances() {
        if (metadataInstances == null) {
            metadataInstances = new ArrayList<Metadata>();
            videoInstances = new ArrayList<VideoStream>();
            hqInstances = new ArrayList<VideoStream>();
            conversionInstances = new ArrayList<Conversion>();
            channelApiInstances = new ArrayList<ChannelApi>();
        } else {
            metadataInstances.clear();
            videoInstances.clear();
            hqInstances.clear();
            conversionInstances.clear();
            channelApiInstances.clear();
        }

        if (configManager == null) {
            configManager = ConfigManager.getInstance();
        }

        Config config = configManager.getConfig();

        List<String> invInstances = config.getInvidiousInstances();
        for (int i = 0; i < invInstances.size(); i++) {
            Invidious inv = new Invidious(invInstances.get(i));
            metadataInstances.add(inv);
            videoInstances.add(inv);
        }

        List<String> yt2009List = config.getYt2009Instances();
        for (int i = 0; i < yt2009List.size(); i++) {
            Yt2009 yt2009 = new Yt2009(yt2009List.get(i));
            videoInstances.add(yt2009);
            hqInstances.add(yt2009);
            conversionInstances.add(yt2009);
            channelApiInstances.add(yt2009);
        }

        List<String> pipedList = config.getPipedInstances();
        for (int i = 0; i < pipedList.size(); i++) {
            String[] parts = pipedList.get(i).split(",", 2);
            String baseUrl = parts[0];
            String proxyUrl = parts.length > 1 ? parts[1] : parts[0];
            Piped piped = new Piped(baseUrl, proxyUrl);
            metadataInstances.add(piped);
            videoInstances.add(piped);
        }

        List<String> ytApiInstances = config.getYtApiLegacyInstances();
        for (int i = 0; i < ytApiInstances.size(); i++) {
            YtApiLegacy ytApi = new YtApiLegacy(ytApiInstances.get(i));
            metadataInstances.add(ytApi);
            videoInstances.add(ytApi);
            hqInstances.add(ytApi);
            conversionInstances.add(ytApi);
        }
    }

    public Metadata getMetadata() {
        if (metadataInstances.isEmpty()) throw new IllegalStateException("No Metadata instances");
        return createStatefulFallbackProxy(Metadata.class, metadataInstances, KEY_METADATA_HOST);
    }

    /** One raw Metadata instance without the fallback proxy. Prefers a
     *  healthy, fast one (random among the best) so icon fallbacks do not
     *  burn their attempts on dead nodes. */
    public Metadata getRandomMetadata() {
        if (metadataInstances == null || metadataInstances.isEmpty()) {
            return null;
        }
        return pickHealthiest(metadataInstances);
    }

    /** One raw Invidious instance, health-picked; falls back to the mixed
     *  pool when no Invidious instance is configured. Used by the channel
     *  icon resolver: Invidious image URLs are served by the instance
     *  itself, while Piped ones need the image proxies that some networks
     *  cannot reach at all. */
    public Metadata getRandomInvidiousMetadata() {
        if (metadataInstances != null && !metadataInstances.isEmpty()) {
            List<Metadata> inv = new ArrayList<Metadata>();
            for (int i = 0; i < metadataInstances.size(); i++) {
                if (metadataInstances.get(i) instanceof Invidious) {
                    inv.add(metadataInstances.get(i));
                }
            }
            if (!inv.isEmpty()) {
                return pickHealthiest(inv);
            }
        }
        return getRandomMetadata();
    }

    /** Picks the best-scored entry of the pool, random among equals. */
    private <T> T pickHealthiest(List<T> pool) {
        if (pool == null || pool.isEmpty()) return null;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < pool.size(); i++) {
            long s = InstanceHealth.score(hostOf(pool.get(i)));
            if (s < best) best = s;
        }
        List<T> tied = new ArrayList<T>();
        for (int i = 0; i < pool.size(); i++) {
            if (InstanceHealth.score(hostOf(pool.get(i))) == best) tied.add(pool.get(i));
        }
        return tied.get(random.nextInt(tied.size()));
    }

    /** A Metadata proxy over the YtApiLegacy instances, or null when none
     *  are configured (YtApiLegacy serves the Popular feed best). */
    public Metadata getPopularMetadata() {
        if (metadataInstances == null || metadataInstances.isEmpty()) {
            return null;
        }

        List<Metadata> ytApiList = new ArrayList<Metadata>();
        for (int i = 0; i < metadataInstances.size(); i++) {
            Metadata m = metadataInstances.get(i);
            if (m instanceof YtApiLegacy) {
                ytApiList.add(m);
            }
        }

        if (ytApiList.isEmpty()) {
            return null;
        }

        return createStatefulFallbackProxy(Metadata.class, ytApiList, KEY_POPULAR_HOST);
    }

    public ChannelApi getChannelApi() {
        if (channelApiInstances.isEmpty()) throw new IllegalStateException("No ChannelApi instances");
        return createStatefulFallbackProxy(ChannelApi.class, channelApiInstances, KEY_CHANNEL_HOST);
    }

    public List<Conversion> getConversion() {
        return conversionInstances;
    }

    /**
     * Subtitle track list served by the configured Piped instances: their
     * caption URLs are currently the only ones that resolve on legacy
     * devices (Invidious bodies get a bot page). The instance that served
     * the last good list is tried first with a light first-bytes probe,
     * then every remaining instance races in parallel; the first healthy
     * list wins. An empty list is definitive ("no captions"); null only
     * when no Piped instance could be asked.
     */
    public List<SubtitleTrack> getPipedSubtitles(String videoId) {
        if (metadataInstances == null || metadataInstances.isEmpty() || videoId == null) return null;
        final String vid = videoId;
        // Copy the pool: instances can be removed while we iterate from a
        // background thread.
        List<Metadata> pool = new ArrayList<Metadata>();
        for (int i = 0; i < metadataInstances.size(); i++) {
            Metadata m = metadataInstances.get(i);
            if (m instanceof Piped) pool.add(m);
        }
        if (pool.isEmpty()) return null;

        final String rememberedHost = rememberedPipedCaptionHost();
        // 1. Fast path: the remembered winner, solo.
        if (rememberedHost != null) {
            for (int i = 0; i < pool.size(); i++) {
                final Metadata m = pool.get(i);
                if (!rememberedHost.equals(hostOf(m))) continue;
                try {
                    List<SubtitleTrack> tracks = ((Piped) m).getSubtitles(vid);
                    if (tracks == null) break;
                    if (tracks.isEmpty()) return tracks; // definitive: no captions
                    if (captionRouteWorks(tracks.get(0))) {
                        rememberPipedCaptionWinner(m);
                        return tracks;
                    }
                } catch (Exception e) {
                    // Remembered winner went bad: fall through to the race.
                }
                break;
            }
        }

        // 2. Race every remaining instance (cheap probe each); the first
        //    healthy list wins, instead of walking dead ones one by one.
        final Object lock = new Object();
        final List[] winner = new List[1];
        final List[] empty = new List[1];
        final List[] bestEffort = new List[1];
        final Metadata[] winnerInstance = new Metadata[1];
        final java.util.concurrent.atomic.AtomicInteger finished =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final List<Metadata> candidates = pool;
        for (int i = 0; i < candidates.size(); i++) {
            final Metadata m = candidates.get(i);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        List<SubtitleTrack> tracks = ((Piped) m).getSubtitles(vid);
                        if (tracks == null) return;
                        synchronized (lock) {
                            if (bestEffort[0] == null) bestEffort[0] = tracks;
                            if (tracks.isEmpty()) {
                                if (empty[0] == null) empty[0] = tracks;
                            } else if (winner[0] == null && captionRouteWorks(tracks.get(0))) {
                                winner[0] = tracks;
                                winnerInstance[0] = m;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        finished.incrementAndGet();
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                }
            }, "pip-caps").start();
        }
        long deadline = System.currentTimeMillis() + CAPTION_PROBE_TIMEOUT_MS;
        synchronized (lock) {
            while (winner[0] == null && finished.get() < candidates.size()
                    && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (winner[0] != null) {
            rememberPipedCaptionWinner(winnerInstance[0]);
            return winner[0];
        }
        if (empty[0] != null) return empty[0];
        return bestEffort[0];
    }

    /** Timeout budget of the caption list race: a picker must not hang. */
    private static final long CAPTION_PROBE_TIMEOUT_MS = 8000;

    // ------------------------------------------------------------------
    // Caption winner memory (persisted, tiny SharedPreferences)
    // ------------------------------------------------------------------
    private static final String CAPTION_PREFS = "caption_cache";
    private static final String KEY_PIPED_HOST = "piped_host";
    private static final String KEY_INV_BASE = "invidious_base";
    private static final String KEY_INV_LABEL = "invidious_label";

    private static String hostOf(Metadata m) {
        try {
            return (String) m.getClass().getMethod("getHost").invoke(m);
        } catch (Exception e) {
            return null;
        }
    }

    /** Same reflection for any pool type (ChannelApi, VideoStream, ...). */
    private static String hostOf(Object instance) {
        try {
            return (String) instance.getClass().getMethod("getHost").invoke(instance);
        } catch (Exception e) {
            return null;
        }
    }

    private void rememberPipedCaptionWinner(Metadata m) {
        String host = hostOf(m);
        if (host == null) return;
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                ctx.getSharedPreferences(CAPTION_PREFS, 0)
                        .edit().putString(KEY_PIPED_HOST, host).commit();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String rememberedPipedCaptionHost() {
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                return ctx.getSharedPreferences(CAPTION_PREFS, 0).getString(KEY_PIPED_HOST, null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Last Invidious base that served a caption body, or null. */
    public static String rememberedInvidiousCaptionBase() {
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                return ctx.getSharedPreferences(CAPTION_PREFS, 0).getString(KEY_INV_BASE, null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Whether the remembered Invidious caption base worked with ?label=. */
    public static boolean rememberedInvidiousCaptionLabel() {
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                return ctx.getSharedPreferences(CAPTION_PREFS, 0).getBoolean(KEY_INV_LABEL, false);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Called when an Invidious captions endpoint served a good body. */
    public static void rememberInvidiousCaptionWinner(String baseUrl, boolean usedLabel) {
        if (baseUrl == null || baseUrl.length() == 0) return;
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                ctx.getSharedPreferences(CAPTION_PREFS, 0).edit()
                        .putString(KEY_INV_BASE, baseUrl)
                        .putBoolean(KEY_INV_LABEL, usedLabel)
                        .commit();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * One cheap probe of a candidate's caption proxy: reads only the
     * first bytes and checks they look like VTT cues (a 403/HTML reply
     * means the route is dead). Legacy TLS cannot complete the https://
     * handshakes most proxies advertise, but most serve the same body
     * over plain http:// — the probe tries that downgrade too.
     */
    private boolean captionRouteWorks(SubtitleTrack track) {
        String url = track.url;
        if (url == null || url.length() == 0) return false;
        if (probeCaptionHead(url)) return true;
        String downgraded = httpDowngrade(url);
        if (downgraded != null && !downgraded.equals(url)) {
            return probeCaptionHead(downgraded);
        }
        return false;
    }

    /** First-bytes caption probe: VTT cue markers within the first 256
     *  characters; an HTML opener means blocked. */
    private boolean probeCaptionHead(String url) {
        InputStream in = null;
        try {
            HttpRequest req = new HttpRequest(url);
            in = HttpClient.execute(req, 6000);
            StringBuffer head = new StringBuffer(64);
            int c;
            while (head.length() < 256 && (c = in.read()) != -1) {
                if (c > 0x7F) continue; // only ASCII matters for the sniff
                head.append((char) c);
                String soFar = head.toString();
                if (soFar.startsWith("<")) return false; // bot / 403 HTML
                if (soFar.startsWith("WEBVTT") || soFar.indexOf("-->") != -1) return true;
            }
            return head.indexOf("-->") != -1;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** https://host/... -> http://host/..., or null when the URL is not
     * https. Raw youtube.com timedtext links are excluded (unreachable
     * from legacy TLS regardless of scheme). */
    public static String httpDowngrade(String url) {
        if (url == null || url.length() == 0) return null;
        if (!url.startsWith("https://")) return null;
        if (url.indexOf("youtube.com/api/timedtext") != -1) return null;
        return "http://" + url.substring(8);
    }

    /**
     * Parses one comment-replies page fetched from the absolute repliesUrl
     * a comment carried. The backend tag selects the JSON schema; the
     * avatar rewriting uses any configured instance of that frontend.
     */
    public List<Comment> parseCommentReplies(int backend, String json) throws IOException {
        List<Metadata> pool = new ArrayList<Metadata>(metadataInstances);
        Metadata parser = null;
        for (int i = 0; i < pool.size(); i++) {
            Metadata m = pool.get(i);
            boolean match = backend == Comment.BACKEND_PIPED
                    ? (m instanceof Piped) : (m instanceof Invidious);
            if (match) {
                parser = m;
                break;
            }
        }
        if (parser == null) {
            throw new IOException("No instance of this frontend is configured");
        }
        if (backend == Comment.BACKEND_PIPED) {
            return ((Piped) parser).parseReplies(json);
        }
        return ((Invidious) parser).parseReplies(json);
    }

    /**
     * Parses a further comments page fetched from the absolute moreUrl a
     * CommentsPage carried; same backend-tag routing as the replies above.
     */
    public CommentsPage parseMoreComments(int backend, String json, String videoId) throws IOException {
        List<Metadata> pool = new ArrayList<Metadata>(metadataInstances);
        Metadata parser = null;
        for (int i = 0; i < pool.size(); i++) {
            Metadata m = pool.get(i);
            boolean match = backend == Comment.BACKEND_PIPED
                    ? (m instanceof Piped) : (m instanceof Invidious);
            if (match) {
                parser = m;
                break;
            }
        }
        if (parser == null) {
            throw new IOException("No instance of this frontend is configured");
        }
        if (backend == Comment.BACKEND_PIPED) {
            return ((Piped) parser).parseMoreComments(json, videoId);
        }
        return ((Invidious) parser).parseMoreComments(json, videoId);
    }

    public String getVideoUrl(String videoId, String quality, int timeout) throws IOException {
        return getVideoUrl(videoId, quality, timeout, null, null);
    }

    public String getVideoUrl(String videoId, String quality, int timeout, VideoStream preferredInstance, VideoStream[] successfulInstance) throws IOException {
        List<VideoStream> targetList = "360".equals(quality) ? new ArrayList<VideoStream>(videoInstances) : new ArrayList<VideoStream>(hqInstances);
        if (targetList.isEmpty()) {
            reloadInstances();
            throw new IOException("No video instances left");
        }

        if (preferredInstance != null && targetList.contains(preferredInstance)) {
            targetList.remove(preferredInstance);
            targetList.add(0, preferredInstance);
            if (targetList.size() > 1) {
                List<VideoStream> rest = targetList.subList(1, targetList.size());
                sortByHealthShuffled(rest);
            }
        } else {
            sortByHealthShuffled(targetList);
        }

        Throwable lastError = null;

        for (int i = 0; i < targetList.size(); i++) {
            VideoStream currentInstance = targetList.get(i);
            long startedAt = System.currentTimeMillis();
            try {
                String url = currentInstance.getVideoUrl(videoId, quality, timeout);
                if (successfulInstance != null && successfulInstance.length > 0) {
                    successfulInstance[0] = currentInstance;
                }
                InstanceHealth.recordSuccess(currentInstance.getHost(),
                        System.currentTimeMillis() - startedAt);
                return url;
            } catch (ContentUnavailableException e) {
                e.printStackTrace();
                InstanceHealth.recordSuccess(currentInstance.getHost(),
                        System.currentTimeMillis() - startedAt); // route answered
                throw e;
            } catch (FileNotFoundException e) {
                InstanceHealth.recordSuccess(currentInstance.getHost(),
                        System.currentTimeMillis() - startedAt); // route answered
                throw e;
            } catch (Exception e) {
                if (isDeadInstanceError(e)) {
                    InstanceHealth.recordFailure(currentInstance.getHost());
                    removeDeadInstance(currentInstance);
                }
                lastError = e;
            }
        }

        if (lastError != null) {
            if (videoInstances.isEmpty() || hqInstances.isEmpty()) {
                reloadInstances();
                showConnectionErrorToast();
            }
            if (lastError instanceof IOException) throw (IOException) lastError;
            throw new IOException(lastError.getMessage());
        }
        throw new IOException("All instances failed");
    }

    public String getConvUrl(String videoId, String quality, int codec) throws IOException {
        return getConvUrl(videoId, quality, codec, null, null);
    }

    public String getConvUrl(String videoId, String quality, int codec, VideoStream preferredInstance, VideoStream[] successfulInstance) throws IOException {
        if (configManager.getConfig().isConvertVideos()) {
            if (conversionInstances.isEmpty()) {
                reloadInstances();
                throw new IOException("No conversion instances left");
            }

            List<Conversion> targetConvList = new ArrayList<Conversion>(conversionInstances);

            // Prioritize the preferred instance when it supports conversion
            if (preferredInstance instanceof Conversion && targetConvList.contains((Conversion) preferredInstance)) {
                targetConvList.remove((Conversion) preferredInstance);
                targetConvList.add(0, (Conversion) preferredInstance);
                if (targetConvList.size() > 1) {
                    List<Conversion> rest = targetConvList.subList(1, targetConvList.size());
                    sortByHealthShuffled(rest);
                }
            } else {
                sortByHealthShuffled(targetConvList);
            }

            for (int i = 0; i < targetConvList.size(); i++) {
                Conversion currentInstance = targetConvList.get(i);
                long startedAt = System.currentTimeMillis();
                try {
                    String url = currentInstance.getConvUrl(videoId, codec);
                    if (successfulInstance != null && successfulInstance.length > 0 && currentInstance instanceof VideoStream) {
                        successfulInstance[0] = (VideoStream) currentInstance;
                    }
                    InstanceHealth.recordSuccess(hostOf(currentInstance),
                            System.currentTimeMillis() - startedAt);
                    return url;
                } catch (Exception e) {
                    if (e instanceof ContentUnavailableException) {
                        InstanceHealth.recordSuccess(hostOf(currentInstance),
                                System.currentTimeMillis() - startedAt);
                        throw (ContentUnavailableException) e;
                    } if (e instanceof VideoTooLongException) {
                        InstanceHealth.recordSuccess(hostOf(currentInstance),
                                System.currentTimeMillis() - startedAt);
                        throw (VideoTooLongException) e;
                    } conversionInstances.remove(currentInstance);
                    if (isDeadInstanceError(e)) {
                        InstanceHealth.recordFailure(hostOf(currentInstance));
                        removeDeadInstance(currentInstance);
                    }
                }
            }
        }

        // Fall back to standard stream URLs when conversion is disabled or failed
        return getVideoUrl(videoId, quality, HttpClient.CONVERSION_TIMEOUT, preferredInstance, successfulInstance);
    }

    /** Health-first ordering with a random shuffle first, so equally
     *  healthy instances still rotate (stable, health-sorted by score). */
    private <T> void sortByHealthShuffled(List<T> list) {
        if (list == null || list.size() <= 1) return;
        java.util.Collections.shuffle(list, random);
        java.util.Collections.sort(list, new java.util.Comparator<T>() {
            public int compare(T a, T b) {
                long sa = InstanceHealth.score(hostOf(a));
                long sb = InstanceHealth.score(hostOf(b));
                return sa < sb ? -1 : (sa > sb ? 1 : 0);
            }
        });
    }

    /** Creates a dynamic proxy that retains one instance for the lifespan
     *  of the Activity, re-picking on failure. */
    @SuppressWarnings("unchecked")
    private <T> T createStatefulFallbackProxy(final Class<T> interfaceClass, final List<T> pool) {
        return createStatefulFallbackProxy(interfaceClass, pool, null);
    }

    /**
     * Same proxy with a "sticky winner": when {@code prefsKey} is set, the
     * first pick prefers the host of the last call that succeeded (same idea
     * as the caption winner memory). Picks are health-ordered (see
     * InstanceHealth): the fast, alive instances serve first, a dead pick
     * costs no random 10-20 s timeout, and every call feeds the latency
     * memory so the ordering keeps improving.
     */
    @SuppressWarnings("unchecked")
    private <T> T createStatefulFallbackProxy(final Class<T> interfaceClass, final List<T> pool, final String prefsKey) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[]{ interfaceClass },
                new InvocationHandler() {
                    private T currentInstance = null;
                    /** Only the very first pick consults the memory. */
                    private boolean firstPick = true;

                    private void pickNewInstance() {
                        if (!pool.isEmpty()) {
                            if (firstPick && prefsKey != null) {
                                firstPick = false;
                                T remembered = findByHost(pool, rememberedHost(prefsKey));
                                if (remembered != null && !InstanceHealth.isDead(hostOf(remembered))) {
                                    currentInstance = remembered;
                                    return;
                                }
                            }
                            currentInstance = pickHealthiest(pool);
                        } else {
                            currentInstance = null;
                        }
                    }

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (currentInstance == null) pickNewInstance();

                        // Cheap local getters are not network probes: timing
                        // them would poison the latency memory.
                        String methodName = method.getName();
                        boolean networkCall = !("getName".equals(methodName)
                                || "getHost".equals(methodName) || "toString".equals(methodName));

                        Throwable lastError = null;
                        List<T> localPool = new ArrayList<T>(pool);

                        while (!localPool.isEmpty()) {
                            if (currentInstance == null || !localPool.contains(currentInstance)) {
                                currentInstance = pickHealthiest(localPool);
                            }

                            long startedAt = System.currentTimeMillis();
                            try {
                                Object result = method.invoke(currentInstance, args);
                                if (prefsKey != null) {
                                    rememberHostIfChanged(prefsKey, hostOf(currentInstance));
                                }
                                if (networkCall) {
                                    InstanceHealth.recordSuccess(hostOf(currentInstance),
                                            System.currentTimeMillis() - startedAt);
                                }
                                return result;
                            } catch (InvocationTargetException e) {
                                if (e.getCause() instanceof ContentUnavailableException) {
                                    // The instance answered: the content
                                    // genuinely does not exist. The route is
                                    // healthy - do not switch instances.
                                    if (networkCall) {
                                        InstanceHealth.recordSuccess(hostOf(currentInstance),
                                                System.currentTimeMillis() - startedAt);
                                    }
                                    throw e.getCause();
                                }
                                e.printStackTrace();
                                lastError = e.getCause();
                                if (networkCall && isDeadInstanceError(lastError)) {
                                    InstanceHealth.recordFailure(hostOf(currentInstance));
                                }
                                localPool.remove(currentInstance);

                                if (isDeadInstanceError(lastError)) {
                                    removeDeadInstance(currentInstance);
                                }
                                currentInstance = null;
                            }
                        }

                        if (pool.isEmpty()) {
                            reloadInstances();
                            showConnectionErrorToast();
                        }

                        if (lastError != null) throw lastError;
                        throw new IOException("No available instances left for " + interfaceClass.getSimpleName());
                    }
                }
        );
    }

    public void removeDeadInstance(Object instance) {
        final String host;
        try {
            host = (String) instance.getClass().getMethod("getHost").invoke(instance);
            notifyDeadInstance(host);
        } catch (Exception ignored) { return; }
        removeByHost(metadataInstances, host);
        removeByHost(videoInstances, host);
        removeByHost(hqInstances, host);
        removeByHost(conversionInstances, host);
        removeByHost(channelApiInstances, host);
    }

    private void removeByHost(List list, String host) {
        java.util.Iterator iterator = list.iterator();
        while (iterator.hasNext()) {
            try {
                Object item = iterator.next();
                String itemHost = (String) item.getClass().getMethod("getHost").invoke(item);
                if (host.equals(itemHost)) {
                    iterator.remove();
                }
            } catch (Exception ignored) {}
        }
    }

    private void notifyDeadInstance(final String name) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context context = NotPipe.getAppContext();
                    if (context != null) {
                        Toast.makeText(context,
                                context.getString(R.string.dead_instance, name),
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void showConnectionErrorToast() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(NotPipe.getAppContext(),
                            "check your internet connection",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    private boolean isDeadInstanceError(Throwable t) {
        if (t == null) return false;

        if (!io.github.gohoski.notpipe.Utils.hasConnection(NotPipe.getAppContext())) {
            return false;
        }

        if (t instanceof SocketTimeoutException ||
                t instanceof ConnectException ||
                t instanceof UnknownHostException) {
            return true;
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Sticky winner memory (SharedPreferences, tiny)
    // ------------------------------------------------------------------
    private static final String INSTANCE_PREFS = "instance_cache";
    private static final String KEY_METADATA_HOST = "metadata_host";
    private static final String KEY_CHANNEL_HOST = "channel_host";
    private static final String KEY_POPULAR_HOST = "popular_host";

    /** The pool entry whose host matches, or null. */
    private static <T> T findByHost(List<T> pool, String host) {
        if (host == null || host.length() == 0) return null;
        for (int i = 0; i < pool.size(); i++) {
            if (host.equals(hostOf(pool.get(i)))) return pool.get(i);
        }
        return null;
    }

    /** Reads the remembered winner host of one capability. */
    private static String rememberedHost(String prefsKey) {
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                return ctx.getSharedPreferences(INSTANCE_PREFS, 0).getString(prefsKey, null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Persists a new winner host; writes only when it actually changed
     *  (a commit is disk I/O on the request thread). */
    private static void rememberHostIfChanged(String prefsKey, String host) {
        if (host == null || host.length() == 0) return;
        if (host.equals(rememberedHost(prefsKey))) return;
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                ctx.getSharedPreferences(INSTANCE_PREFS, 0)
                        .edit().putString(prefsKey, host).commit();
            }
        } catch (Throwable ignored) {
        }
    }

    public void reloadInstances() {
        initializeInstances();
    }

    public List<VideoStream> getVideoInstances() {
        return videoInstances;
    }

    public List<VideoStream> getHqInstances() {
        return hqInstances;
    }

    public static class InstanceInfo {
        public VideoStream instance;
        public String host;
        public String name;
        public boolean supportsAllQualities;
    }

    /** Snapshot of the playable instances for the picker dialog. A pure
     *  read: re-initializing here would resurrect instances already
     *  removed as dead (and rebuild every list on the UI thread). */
    public List<InstanceInfo> videoInstancesInfo() {
        if (videoInstances == null || hqInstances == null) {
            initializeInstances(); // first call before any init
        }
        List<InstanceInfo> result = new ArrayList<InstanceInfo>();
        List<VideoStream> allKnown = new ArrayList<VideoStream>();
        List<String> processed = new ArrayList<String>();
        allKnown.addAll(videoInstances);
        allKnown.addAll(hqInstances);

        for (int i = 0; i < allKnown.size(); i++) {
            VideoStream instance = allKnown.get(i);
            InstanceInfo info = new InstanceInfo();
            info.instance = instance;
            info.host = instance.getHost();
            info.name = instance.getName();
            info.supportsAllQualities = !(instance instanceof Invidious) && !(instance instanceof Piped);
            if (!processed.contains(info.host)) {
                result.add(info);
                processed.add(info.host);
            }
        }

        return result;
    }
}