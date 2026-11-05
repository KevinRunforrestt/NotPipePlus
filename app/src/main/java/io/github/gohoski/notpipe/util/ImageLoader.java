package io.github.gohoski.notpipe.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import io.github.gohoski.notpipe.NotPipe;
import io.github.gohoski.notpipe.util.ImageRoutes;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Created by Gleb on 29.01.2026 Utility class for loading images from URLs with optional memory caching. */
public class ImageLoader {
    private static final Hashtable<String, Bitmap> memoryCache = new Hashtable<String, Bitmap>();
    private static final LinkedList<String> cacheHistory = new LinkedList<String>();
    private static final Hashtable<String, List<ImageRequest>> pendingViews = new Hashtable<String, List<ImageRequest>>();
    /** 2011-era hardware: 2 slow cores, a small VM heap. */
    private static final int DECODE_THREADS = 3;
    private static final int MAX_CACHE_SIZE = 30;
    /** Byte cap of the memory cache: entry count alone let 30 ARGB thumbnails hold ~15 MB. */
    private static final long MAX_CACHE_BYTES = 4L * 1024L * 1024L;
    /** Longest-edge caps: thumbnails never need more than 480 px, avatars never more than 96 px. */
    private static final int MAX_THUMB_DIM = 480;
    private static final int MAX_AVATAR_DIM = 96;
    private static final ExecutorService executor = Executors.newFixedThreadPool(DECODE_THREADS);
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());
    /** Approximate bytes held by the cached bitmaps (guarded by ImageLoader.class together with cacheHistory). */
    private static long cacheBytes = 0L;

    public interface ImageLoaderCallback {
        void onFail();
    }

    public static class ImageRequest {
        private final java.lang.ref.WeakReference<ImageView> viewRef;
        public final ImageLoaderCallback callback;

        public ImageRequest(ImageView view, ImageLoaderCallback callback) {
            this.viewRef = view != null ? new java.lang.ref.WeakReference<ImageView>(view) : null;
            this.callback = callback;
        }

        public ImageView getView() {
            return viewRef != null ? (ImageView) viewRef.get() : null;
        }
    }

    /** Load an image from URL into an ImageView (memory cache enabled). */
    public static void loadImage(final String url, final ImageView imageView) {
        loadImage(url, imageView, true, null, MAX_THUMB_DIM);
    }

    /** Load a small channel avatar into an ImageView with caching enabled. */
    public static void loadAvatar(final String url, final ImageView imageView) {
        loadAvatar(url, imageView, null);
    }

    /** Same, with a fail callback (used by the channel icon resolver's fallback chain). */
    public static void loadAvatar(final String url, final ImageView imageView, final ImageLoaderCallback callback) {
        loadImage(url, imageView, true, callback, MAX_AVATAR_DIM);
    }

    /** Preload an image into cache without displaying it. */
    public static void preloadImage(final String url) {
        loadImage(url, null, true, null, MAX_THUMB_DIM);
    }

    /** Same, with an explicit cache flag and an optional fail callback. */
    public static synchronized void loadImage(final String url, final ImageView imageView,
            final boolean useCache, final ImageLoaderCallback callback) {
        loadImage(url, imageView, useCache, callback, 0);
    }

    /** Same, downscaled so the longest edge stays within maxDimPx. */
    public static synchronized void loadImage(final String url, final ImageView imageView,
            final boolean useCache, final ImageLoaderCallback callback, final int maxDimPx) {
        if (url == null || url.length() == 0) {
            if (callback != null) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onFail();
                    }
                });
            }
            return;
        }
        if (imageView != null) {
            imageView.setTag(url);
        }
        if (useCache && memoryCache.containsKey(url)) {
            Bitmap cachedBitmap = memoryCache.get(url);
            if (imageView != null && cachedBitmap != null && !cachedBitmap.isRecycled()) {
                imageView.setImageBitmap(cachedBitmap);
            } else if (cachedBitmap != null && cachedBitmap.isRecycled()) {
                memoryCache.remove(url);
                cacheHistory.remove(url);
            }
            return;
        }
        if (pendingViews.containsKey(url)) {
            pendingViews.get(url).add(new ImageRequest(imageView, callback));
            return;
        }
        final List<ImageRequest> views = new ArrayList<ImageRequest>();
        views.add(new ImageRequest(imageView, callback));
        pendingViews.put(url, views);
        executor.execute(new ImageLoadRunnable(url, useCache, maxDimPx));
    }

    /** Decodes a stream, optionally downscaled (two-pass bounds decode). */
    private static Bitmap decodeStream(InputStream is, int maxDimPx) {
        // Always read fully into a byte array first: purgeable decoding needs the source bytes retained.
        byte[] data;
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(8192);
            byte[] chunk = new byte[4096];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
                if (buffer.size() > 4 * 1024 * 1024) break; // sanity cap
            }
            data = buffer.toByteArray();
        } catch (OutOfMemoryError e) {
            Log.e("ImageLoader", "OOM buffering image");
            return null;
        } catch (Exception e) {
            return null;
        }
        if (data == null || data.length == 0) return null;

        // Sample size for the downscale request (stays 1 at native size).
        int sample = 1;
        if (maxDimPx > 0) {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                int longest = Math.max(bounds.outWidth, bounds.outHeight);
                while (longest / (sample * 2) >= maxDimPx) {
                    sample *= 2;
                }
            } catch (Exception ignored) {
                sample = 1;
            }
        }

        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            // Thumbnails decode at 16-bit color (parent client's choice): half the bytes of ARGB.
            if (maxDimPx >= MAX_THUMB_DIM) {
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
            }
            if (NotPipe.SDK < 11) {
                // Bitmap pixels off the VM heap: the VM frees them under pressure and re-decodes on demand instead of freezing.
                opts.inPurgeable = true;
                opts.inInputShareable = true;
            }
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (OutOfMemoryError e) {
            Log.e("ImageLoader", "OOM decoding image");
            freeOldestHalf();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static class ImageLoadRunnable implements Runnable {
        private final String url;
        private final boolean useCache;
        private final int maxDimPx;

        ImageLoadRunnable(String url, boolean useCache, int maxDimPx) {
            this.url = url;
            this.useCache = useCache;
            this.maxDimPx = maxDimPx;
        }

        @Override
        public void run() {
            final int MAX_RETRIES = 2;
            Bitmap bmp = null;

            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    URL aURL = new URL(url);
                    HttpURLConnection conn = (HttpURLConnection) aURL.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0");
                    conn.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5");
                    conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-GB;q=0.8,en;q=0.7");
                    conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br, zstd");
                    conn.setRequestProperty("Referer", "https://www.youtube.com/");
                    conn.setRequestProperty("Sec-Fetch-Storage-Access", "active");
                    conn.setRequestProperty("Sec-Fetch-Dest", "image");
                    conn.setRequestProperty("Sec-Fetch-Mode", "no-cors");
                    conn.setRequestProperty("Sec-Fetch-Site", "cross-site");
                    conn.setRequestProperty("Priority", "u=5, i");
                    conn.setRequestProperty("TE", "trailers");
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    bmp = decodeStream(is, maxDimPx);
                    is.close();
                    break;
                } catch (java.net.SocketTimeoutException e) {
                    if (attempt == MAX_RETRIES) {
                        Log.e("ImageLoader", "Final attempt failed. Giving up.");
                    } else {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                } catch (OutOfMemoryError e) {
                    Log.e("ImageLoader", "OutOfMemoryError loading " + url + ", attempting to free memory");
                    System.gc();
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    if (attempt == MAX_RETRIES) break;
                } catch (Exception e) {
                    Log.e("ImageLoader", "Download error: " + e.getMessage());
                    break;
                }
            }
            ImageRoutes.reportResult(url, bmp != null);

            // Self-healing: images on an unreachable base are retried on the next live base.
            String attempted = url;
            for (int alt = 0; alt < ImageRoutes.MAX_ALTERNATIVES && bmp == null; alt++) {
                String next = ImageRoutes.nextAlternative(attempted);
                if (next == null) break;
                attempted = next;
                bmp = fetchAlternative(attempted);
                ImageRoutes.reportResult(attempted, bmp != null);
            }

            final Bitmap finalBmp = bmp;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    List<ImageRequest> pendingList;
                    synchronized (ImageLoader.class) {
                        List<ImageRequest> stored = pendingViews.get(url);
                        if (stored == null) {
                            // Every request was cancelled while the decode ran (rows recycled by a fast scroll): nothing to deliver.
                            return;
                        }
                        pendingList = new ArrayList<ImageRequest>(stored);
                        pendingViews.remove(url);
                    }

                    if (finalBmp != null && !finalBmp.isRecycled()) {
                        if (useCache) {
                            putInCache(url, finalBmp);
                        }

                        for (ImageRequest req : pendingList) {
                            ImageView reqView = req.getView();
                            if (reqView != null) {
                                String currentTag = (String) reqView.getTag();
                                if (url.equals(currentTag)) {
                                    reqView.setImageBitmap(finalBmp);
                                }
                            }
                        }
                    } else {
                        for (ImageRequest req : pendingList) {
                            if (req.callback != null) {
                                req.callback.onFail();
                            }
                        }
                    }
                }
            });
        }

        /** Single-shot fetch of an alternative URL: the same headers and timeouts as the main loop. */
        private Bitmap fetchAlternative(String u) {
            try {
                URL aURL = new URL(u);
                HttpURLConnection conn = (HttpURLConnection) aURL.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0");
                conn.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5");
                conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-GB;q=0.8,en;q=0.7");
                conn.setRequestProperty("Accept-Encoding", "gzip, deflate, br, zstd");
                conn.setRequestProperty("Referer", "https://www.youtube.com/");
                conn.setRequestProperty("Sec-Fetch-Storage-Access", "active");
                conn.setRequestProperty("Sec-Fetch-Dest", "image");
                conn.setRequestProperty("Sec-Fetch-Mode", "no-cors");
                conn.setRequestProperty("Sec-Fetch-Site", "cross-site");
                conn.setRequestProperty("Priority", "u=5, i");
                conn.setRequestProperty("TE", "trailers");
                conn.connect();
                InputStream is = conn.getInputStream();
                Bitmap bmp = decodeStream(is, maxDimPx);
                is.close();
                return bmp;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Cancel loading for a specific ImageView. */
    public static void cancelLoad(ImageView imageView) {
        if (imageView == null) {
            return;
        }
        imageView.setTag(null);
        synchronized (ImageLoader.class) {
            java.util.Enumeration<String> keys = pendingViews.keys();
            while (keys.hasMoreElements()) {
                String key = keys.nextElement();
                List<ImageRequest> list = pendingViews.get(key);
                if (list == null) {
                    continue;
                }
                for (int i = list.size() - 1; i >= 0; i--) {
                    ImageView view = list.get(i).getView();
                    if (view == null || view == imageView) {
                        list.remove(i);
                    }
                }
                if (list.isEmpty()) {
                    pendingViews.remove(key);
                }
            }
        }
    }

    /** Cancel any pending load for the view and detach its bitmap so the hosting activity can be collected. */
    public static void releaseView(ImageView imageView) {
        if (imageView == null) {
            return;
        }
        cancelLoad(imageView);
        try {
            imageView.setImageBitmap(null);
        } catch (Exception ignored) {}
    }

    /** Detaches a view and cancels its pending loads (row recycled). */
    public static void unbindImageView(ImageView imageView) {
        if (imageView == null) {
            return;
        }
        cancelLoad(imageView);
        try {
            imageView.setImageDrawable(null);
        } catch (Throwable ignored) {}
    }

    /** Cancel all pending image loads. Useful when clearing a list. */
    public static void cancelAllLoads() {
        synchronized (ImageLoader.class) {
            pendingViews.clear();
        }
    }

    /** Clears the memory cache to free memory. */
    public static void clearCache() {
        synchronized (ImageLoader.class) {
            memoryCache.clear();
            cacheHistory.clear();
            cacheBytes = 0L;
        }
        System.gc();
    }

    // --- Cache bookkeeping (all under ImageLoader.class) ---

    /** Puts a decoded bitmap in the cache, evicting the oldest entries until BOTH caps. */
    private static void putInCache(String url, Bitmap bmp) {
        long bytes = approxBytes(bmp);
        if (bytes > MAX_CACHE_BYTES) return; // never let one image eat the cache
        synchronized (ImageLoader.class) {
            if (cacheHistory.contains(url)) {
                cacheHistory.remove(url);
                Bitmap old = memoryCache.remove(url);
                if (old != null && old != bmp) cacheBytes -= approxBytes(old);
            }
            cacheHistory.addLast(url);
            memoryCache.put(url, bmp);
            cacheBytes += bytes;
            while (cacheHistory.size() > MAX_CACHE_SIZE || cacheBytes > MAX_CACHE_BYTES) {
                String oldest = cacheHistory.removeFirst();
                Bitmap gone = memoryCache.remove(oldest);
                if (gone != null) cacheBytes -= approxBytes(gone);
            }
        }
    }

    /** Drops the oldest half of the cache under memory pressure (an OOM mid-decode frees room for the retry). */
    private static void freeOldestHalf() {
        synchronized (ImageLoader.class) {
            int drop = cacheHistory.size() / 2;
            for (int i = 0; i < drop; i++) {
                String oldest = cacheHistory.removeFirst();
                Bitmap gone = memoryCache.remove(oldest);
                if (gone != null) cacheBytes -= approxBytes(gone);
            }
        }
    }

    /** Exact byte size of a bitmap's pixel storage (row bytes x height, API-1 safe). */
    private static long approxBytes(Bitmap b) {
        try {
            return (long) b.getRowBytes() * (long) b.getHeight();
        } catch (Throwable t) {
            return 0L;
        }
    }
}