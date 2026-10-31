package io.github.gohoski.notpipe.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

import io.github.gohoski.notpipe.NotPipe;

/**
 * Per-host health and speed memory for the API instances, persisted across
 * launches: every timed call feeds an exponential moving average of the
 * latency, every failure counts up and parks the host in a short "dead"
 * window. Pickers ask {@link #isDead} and {@link #score} to try healthy,
 * fast instances first instead of random ones — a random pick that lands
 * on a dead node costs a 10-20 s timeout, which is what made loading feel
 * slow. Stats recover automatically: a dead window expires and a success
 * lowers the score again.
 */
public final class InstanceHealth {

    private static final String PREFS = "instance_health";

    /** A failed host is skipped this long before being probed again. */
    private static final long DEAD_WINDOW_MS = 5L * 60L * 1000L;
    /** EWMA weight of a new sample (0..1): higher reacts faster. */
    private static final float SMOOTHING = 0.3f;
    /** Latency (ms) assumed for a host with no data yet: probes it early. */
    private static final long UNKNOWN_LATENCY_MS = 1500L;

    private static final Map<String, Float> latency = new HashMap<String, Float>();
    private static final Map<String, Long> deadUntil = new HashMap<String, Long>();
    private static final Map<String, Long> lastPersist = new HashMap<String, Long>();
    private static boolean loaded = false;

    private InstanceHealth() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx == null) return;
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, 0);
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                try {
                    latency.put(e.getKey(), Float.valueOf(String.valueOf(e.getValue())));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** Call after a successful request; durationMs is the wall time it took. */
    public static void recordSuccess(String host, long durationMs) {
        if (host == null || host.length() == 0) return;
        ensureLoaded();
        synchronized (InstanceHealth.class) {
            deadUntil.remove(host);
            Float prev = latency.get(host);
            float sample = durationMs < 0 ? 0 : durationMs;
            float next = prev == null ? sample : prev * (1f - SMOOTHING) + sample * SMOOTHING;
            latency.put(host, next);
            persist(host, next);
        }
    }

    /** Call when a request failed with a connectivity-class error. */
    public static void recordFailure(String host) {
        if (host == null || host.length() == 0) return;
        ensureLoaded();
        synchronized (InstanceHealth.class) {
            deadUntil.put(host, System.currentTimeMillis() + DEAD_WINDOW_MS);
        }
    }

    /** True while the host sits in the dead window after a failure. */
    public static boolean isDead(String host) {
        if (host == null) return false;
        ensureLoaded();
        synchronized (InstanceHealth.class) {
            Long until = deadUntil.get(host);
            if (until == null) return false;
            if (until.longValue() <= System.currentTimeMillis()) {
                deadUntil.remove(host);
                return false;
            }
        }
        return true;
    }

    /**
     * Sort key for pickers: smaller = try first. Dead hosts sort last; then
     * faster hosts before slower ones; hosts without data come after known
     * -good ones so the measured winners keep serving.
     */
    public static long score(String host) {
        if (host == null) return Long.MAX_VALUE;
        ensureLoaded();
        synchronized (InstanceHealth.class) {
            if (isDead(host)) return Long.MAX_VALUE - 1;
            Float l = latency.get(host);
            if (l == null) return UNKNOWN_LATENCY_MS * 2;
            return (long) l.floatValue();
        }
    }

    /** Persists one host's latency, throttled (no disk I/O per request). */
    private static void persist(String host, float value) {
        long now = System.currentTimeMillis();
        Long last = lastPersist.get(host);
        if (last != null && now - last.longValue() < 60000L) return;
        lastPersist.put(host, now);
        try {
            Context ctx = NotPipe.getAppContext();
            if (ctx != null) {
                // apply() needs API 9; commit() works everywhere on a
                // background thread.
                ctx.getSharedPreferences(PREFS, 0).edit()
                        .putString(host, String.valueOf(value)).commit();
            }
        } catch (Throwable ignored) {}
    }
}
