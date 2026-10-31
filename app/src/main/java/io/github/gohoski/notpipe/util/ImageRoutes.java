package io.github.gohoski.notpipe.util;

import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;

import java.util.List;

/**
 * Central router for remote images (video thumbnails, avatars, banners).
 *
 * Every image URL the app builds is served by an Invidious instance instead
 * of the Piped image proxies: the proxies turned out to be unreachable from
 * some networks the Kindle Fire sits on (thumbnails and avatars never loaded
 * there while the very same instance list worked everywhere else), while the
 * Invidious image routes proved reachable on that device — the subscriptions
 * feed has always loaded its thumbnails this way.
 *
 * Routes (identical to what a Piped row needs, both verified against live
 * instances to answer image/jpeg):
 *   /vi/{videoId}/mqdefault.jpg          video thumbnails
 *   /ggpht/{ggpht path}                  avatars / banners
 *
 * Which instance serves the images is NOT random anymore. The original
 * 0.4.1 release picked a random base and stuck with it: on a network where
 * most community instances are unreachable that blanked every thumbnail of
 * the session (and its failure counter was reset by unrelated avatar
 * successes, so the rotation never escaped the bad base). The bases are now
 * ordered by the measured health of the API hosts (InstanceHealth: known
 * latency first, unknown after, dead last — persisted between sessions),
 * a base that fails images is parked for a short window, and every failed
 * image is retried on the next live base by ImageLoader (bounded to
 * MAX_ALTERNATIVES extra bases), so one dead node can never blank a screen.
 */
public final class ImageRoutes {
    /** Extra bases tried per image after the first one fails. */
    static final int MAX_ALTERNATIVES = 3;
    /** Image failures before a base is parked: one — every failed base
     *  must cost exactly one attempt so an image's bounded alternative
     *  chain marches forward instead of bouncing between dead nodes
     *  (a parked base recovers by itself after PARK_MS). */
    private static final int FAILS_BEFORE_PARK = 1;
    /** How long a parked base stays out of rotation. */
    private static final long PARK_MS = 2L * 60L * 1000L;

    private static String[] bases = new String[0];
    private static int[] baseFails = new int[0];
    private static long[] parkedUntil = new long[0];
    private static int idx;
    private static boolean initialized;

    private ImageRoutes() {}

    /** Video thumbnail URL on the current Invidious base; "" when no base. */
    public static String videoThumb(String videoId) {
        if (videoId == null || videoId.length() == 0) return "";
        String b = currentBase();
        if (b == null) return "";
        return b + "/vi/" + videoId + "/mqdefault.jpg";
    }

    /** Avatar / banner URL: any ggpht-shaped URL (Piped proxy form, direct
     *  yt3.ggpht form or instance-relative form) is rerouted through the
     *  /ggpht image route of the current Invidious base, upgraded from
     *  webp to jpeg flags along the way; "" when no base. */
    public static String avatar(String raw) {
        if (raw == null || raw.length() == 0) return "";
        String b = currentBase();
        if (b == null) return "";
        String path = ggphtPath(raw);
        if (path == null || path.length() == 0) return "";
        return b + "/ggpht/" + path;
    }

    /**
     * Outcome of one image request. Unlike the original release this counts
     * per base (the URL says which one served it), so a success on one base
     * no longer resets the failure count of another. FAILS_BEFORE_PARK
     * consecutive failures park the base for PARK_MS.
     */
    public static void reportResult(String url, boolean ok) {
        if (url == null) return;
        synchronized (ImageRoutes.class) {
            if (!initialized) initBases();
            if (bases.length == 0) return;
            int i = baseOf(url);
            if (i == -1) return; // not our route
            if (ok) {
                baseFails[i] = 0;
                return;
            }
            baseFails[i]++;
            if (baseFails[i] >= FAILS_BEFORE_PARK) {
                baseFails[i] = 0;
                parkedUntil[i] = System.currentTimeMillis() + PARK_MS;
                if (i == idx) {
                    int n = nextLiveFrom(i);
                    if (n != -1) idx = n; // none live: currentBase() handles it
                }
            }
        }
    }

    /**
     * Same image on the next live base, for the retry loop of ImageLoader:
     * the failed URL's base is skipped (as are parked and API-dead bases).
     * The scan starts AT the current base — after a park it is exactly the
     * next best candidate. Null when the URL is not routed through this
     * class or no live base is left — the caller then gives up as before.
     */
    public static String nextAlternative(String failedUrl) {
        if (failedUrl == null) return null;
        synchronized (ImageRoutes.class) {
            if (!initialized) initBases();
            if (bases.length == 0) return null;
            int failed = baseOf(failedUrl);
            if (failed == -1) return null;
            String path = failedUrl.substring(bases[failed].length());
            for (int step = 0; step < bases.length; step++) {
                int i = (idx + step) % bases.length;
                if (i == failed) continue;
                if (!isLive(i)) continue;
                return bases[i] + path;
            }
            return null;
        }
    }

    /** True while a base can serve images: not parked here and not sitting
     *  in the shared API-health dead window. */
    private static boolean isLive(int i) {
        if (parkedUntil[i] > System.currentTimeMillis()) return false;
        return !InstanceHealth.isDead(hostOf(bases[i]));
    }

    /** The currently used base, advancing past dead ones; null when the
     *  pool is empty. When everything is parked the base whose parking
     *  expires first is returned (retrying beats a guaranteed blank). */
    private static String currentBase() {
        synchronized (ImageRoutes.class) {
            if (!initialized) initBases();
            if (bases.length == 0) return null;
            if (isLive(idx)) return bases[idx];
            int next = nextLiveFrom(idx);
            if (next != -1) {
                idx = next;
                return bases[idx];
            }
            // All parked: earliest to expire keeps the images coming.
            int best = 0;
            for (int i = 1; i < parkedUntil.length; i++) {
                if (parkedUntil[i] < parkedUntil[best]) best = i;
            }
            idx = best;
            return bases[idx];
        }
    }

    /** First live base at or after "from" (cyclic), -1 when none. */
    private static int nextLiveFrom(int from) {
        for (int step = 0; step < bases.length; step++) {
            int i = (from + step) % bases.length;
            if (isLive(i)) return i;
        }
        return -1;
    }

    /** Index of the base a URL was built with, -1 when foreign. */
    private static int baseOf(String url) {
        for (int i = 0; i < bases.length; i++) {
            if (url.startsWith(bases[i] + "/")) return i;
        }
        return -1;
    }

    /** Loads the Invidious instance list from the config, best-health
     *  first (stable: equal scores keep the config order). */
    private static void initBases() {
        initialized = true;
        bases = new String[0];
        baseFails = new int[0];
        parkedUntil = new long[0];
        idx = 0;
        try {
            Config config = ConfigManager.getInstance().getConfig();
            List<String> inv = config.getInvidiousInstances();
            if (inv == null || inv.isEmpty()) return;
            bases = new String[inv.size()];
            int n = 0;
            for (int i = 0; i < inv.size(); i++) {
                String b = inv.get(i) == null ? "" : inv.get(i).trim();
                while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
                if (b.length() == 0) continue;
                bases[n++] = b;
            }
            if (n == 0) {
                bases = new String[0];
                return;
            }
            if (n != bases.length) {
                String[] trimmed = new String[n];
                System.arraycopy(bases, 0, trimmed, 0, n);
                bases = trimmed;
            }
            // Health-ordered insertion sort (Java 1.5 compatible, stable).
            long[] scores = new long[bases.length];
            for (int i = 0; i < bases.length; i++) scores[i] = InstanceHealth.score(hostOf(bases[i]));
            for (int i = 1; i < bases.length; i++) {
                String b = bases[i];
                long s = scores[i];
                int j = i - 1;
                while (j >= 0 && scores[j] > s) {
                    bases[j + 1] = bases[j];
                    scores[j + 1] = scores[j];
                    j--;
                }
                bases[j + 1] = b;
                scores[j + 1] = s;
            }
            baseFails = new int[bases.length];
            parkedUntil = new long[bases.length];
            idx = 0;
        } catch (Throwable t) {
            bases = new String[0];
            baseFails = new int[0];
            parkedUntil = new long[0];
        }
    }

    /** Base URL without scheme, the host key InstanceHealth uses. */
    private static String hostOf(String base) {
        if (base == null) return "";
        if (base.startsWith("https://")) return base.substring(8);
        if (base.startsWith("http://")) return base.substring(7);
        return base;
    }

    /** Extracts the ggpht path of an avatar/banner URL and upgrades its
     *  webp format flag to jpeg (Android 2.3 cannot decode webp). Handles
     *  the Piped proxy forms ("...-no-rw?host=", "...-no-rw-mo?host="),
     *  direct ggpht URLs and instance-relative paths. */
    private static String ggphtPath(String raw) {
        String s = raw.trim();
        if (s.startsWith("http://") || s.startsWith("https://")) {
            int afterScheme = s.indexOf("://") + 3;
            int slash = s.indexOf('/', afterScheme);
            if (slash == -1) return null; // host only, no path
            s = s.substring(slash + 1);
        } else if (s.startsWith("/")) {
            s = s.substring(1);
        }
        // Instance-relative avatar URLs may already carry the route prefix.
        if (s.startsWith("ggpht/")) {
            s = s.substring(6);
        }
        // Piped proxy paths arrive base64-encoded with the format flag
        // before "?host="; an Invidious /ggpht route accepts the path as
        // is but answers webp unless the flag says jpeg.
        int hostAt = s.indexOf("?host=");
        String head = hostAt == -1 ? s : s.substring(0, hostAt);
        String tail = hostAt == -1 ? "" : s.substring(hostAt);
        if (head.endsWith("-rw-mo")) {
            head = head.substring(0, head.length() - 6) + "-rj-mo";
        } else if (head.endsWith("-rw")) {
            head = head.substring(0, head.length() - 3) + "-rj";
        }
        s = head + tail;
        if (s.length() == 0) return null;
        return s;
    }
}
