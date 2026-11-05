package io.github.gohoski.notpipe.util;

import android.os.Handler;
import android.widget.ImageView;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;

/** Shared resolver for channel icons fetched by channel ID when a video row misses the channel thumbnail URL. */
public class ChannelIconResolver {
    public static final String FAILED = "FAILED";

    /** Distinct metadata instances one fallback may try before declaring the icon unresolvable for now. */
    private static final int MAX_ICON_ATTEMPTS = 3;
    /** A failed channel waits this long before another resolution. */
    private static final long RETRY_COOLDOWN_MS = 2L * 60L * 1000L;

    private static final Hashtable<String, String> resolvedChannelIcons = new Hashtable<String, String>();
    private static final Hashtable<String, Boolean> fetchingChannelIcons = new Hashtable<String, Boolean>();
    /** Wall time of the last failed resolution per channel. */
    private static final Map<String, Long> failedAt = new Hashtable<String, Long>();
    private static final ExecutorService channelIconExecutor = Executors.newFixedThreadPool(4);

    public interface OnIconResolvedListener {
        /** Callback invoked on the main thread when a background icon fetch finishes. */
        void onIconResolved(String channelId, String url);
    }

    /** Returns the previously resolved icon URL for a channel, or null if not yet resolved. */
    public static String getResolved(String channelId) {
        if (channelId == null) return null;
        String cached = resolvedChannelIcons.get(channelId);
        if (FAILED.equals(cached) && cooldownExpired(channelId)) {
            return null; // old failure: allow a fresh resolution
        }
        return cached;
    }

    private static boolean cooldownExpired(String channelId) {
        Long at = failedAt.get(channelId);
        return at == null || System.currentTimeMillis() - at.longValue() >= RETRY_COOLDOWN_MS;
    }

    /** Pre-populates the cache with a known icon URL for future getResolved calls. */
    public static void setResolved(String channelId, String url) {
        if (channelId == null || url == null) return;
        if (url.length() == 0) return;
        resolvedChannelIcons.put(channelId, url);
        failedAt.remove(channelId);
    }

    /** Requests a background fetch of the channel icon. */
    public static void requestFallback(final String channelId, final OnIconResolvedListener listener) {
        if (channelId == null || channelId.length() == 0
                || fetchingChannelIcons.containsKey(channelId)) {
            return;
        }
        String cached = resolvedChannelIcons.get(channelId);
        if (cached != null && !(FAILED.equals(cached) && cooldownExpired(channelId))) {
            return; // resolved, or a fresh failure still cooling down
        }
        fetchingChannelIcons.put(channelId, true);
        channelIconExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String fetchedUrl = null;
                try {
                    // Try DISTINCT instances, healthiest first, preferring Invidious: its icon URLs are served by the instance itself.
                    List<Metadata> tried = new ArrayList<Metadata>();
                    for (int attempt = 0; attempt < MAX_ICON_ATTEMPTS; attempt++) {
                        if (fetchedUrl != null && fetchedUrl.length() > 0) break;
                        Metadata fallbackApi = attempt == 0
                                ? Manager.getInstance().getRandomInvidiousMetadata()
                                : Manager.getInstance().getRandomMetadata();
                        if (fallbackApi == null) break; // no instances at all
                        if (tried.contains(fallbackApi) && attempt < MAX_ICON_ATTEMPTS - 1) {
                            continue; // same pick as before: get another one
                        }
                        tried.add(fallbackApi);
                        try {
                            fetchedUrl = fallbackApi.getChannelIcon(channelId);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                final String resultUrl = fetchedUrl;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        fetchingChannelIcons.remove(channelId);
                        if (resultUrl != null && resultUrl.length() > 0) {
                            resolvedChannelIcons.put(channelId, resultUrl);
                            failedAt.remove(channelId);
                        } else {
                            resolvedChannelIcons.put(channelId, FAILED);
                            failedAt.put(channelId, Long.valueOf(System.currentTimeMillis()));
                        }
                        if (listener != null) {
                            listener.onIconResolved(channelId, resultUrl);
                        }
                    }
                });
            }
        });
    }

    /** Loads a channel icon into the given view, preferring an icon URL that was previously resolved for the channel. */
    public static void loadInto(final ImageView view, final String channelId, final String thumbnailUrl, final OnIconResolvedListener resolveListener) {
        String url = thumbnailUrl;
        if (channelId != null && channelId.length() > 0) {
            String resolved = getResolved(channelId);
            if (FAILED.equals(resolved)) return;
            if (resolved != null) url = resolved;
        }
        if (url == null || url.length() == 0) {
            requestFallback(channelId, resolveListener);
        } else {
            ImageLoader.loadAvatar(url, view, new ImageLoader.ImageLoaderCallback() {
                @Override
                public void onFail() {
                    if (getResolved(channelId) == null) {
                        requestFallback(channelId, resolveListener);
                    }
                }
            });
        }
    }
}
