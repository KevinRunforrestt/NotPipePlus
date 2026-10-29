package io.github.gohoski.notpipe.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import io.github.gohoski.notpipe.NotPipe;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Gleb on 29.01.2026
 * Utility class for loading images from URLs with optional memory caching
 */
public class ImageLoader {
    private static final Hashtable<String, Bitmap> memoryCache = new Hashtable<String, Bitmap>();
    private static final LinkedList<String> cacheHistory = new LinkedList<String>();
    private static final Hashtable<String, List<ImageRequest>> pendingViews = new Hashtable<String, List<ImageRequest>>();
    /** 2011-era hardware: 2 slow cores, a small VM heap. Three parallel
     *  decoders still fill a two-column feed fast without starving the UI
     *  thread (six caused the wavy scrolling). */
    private static final int DECODE_THREADS = 3;
    private static final int MAX_CACHE_SIZE = 30;
    private static final ExecutorService executor = Executors.newFixedThreadPool(DECODE_THREADS);
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());

    public interface ImageLoaderCallback {
        void onFail();
    }

    public static class ImageRequest {
        public ImageView view;
        public ImageLoaderCallback callback;

        public ImageRequest(ImageView view, ImageLoaderCallback callback) {
            this.view = view;
            this.callback = callback;
        }
    }

    /** Load an image from URL into an ImageView (memory cache enabled). */
    public static void loadImage(final String url, final ImageView imageView) {
        loadImage(url, imageView, true, null);
    }

    /** Same, with an explicit cache flag and an optional fail callback. */
    public static synchronized void loadImage(final String url, final ImageView imageView,
            final boolean useCache, final ImageLoaderCallback callback) {
        loadImage(url, imageView, useCache, callback, 0);
    }

    /** Same, downscaled so the longest edge stays within maxDimPx (banners
     *  and avatars decode at a fraction of the source size on 512 MB
     *  devices); the scaled bitmap is what gets cached. */
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

    /** Decodes a stream, optionally downscaled so the longest edge stays
     *  within maxDimPx (two-pass bounds decode, API-1 compatible).
     *  On Gingerbread the pixels live on the native heap via inPurgeable:
     *  without it every decode allocates hundreds of KB on the VM heap and
     *  the collection pauses show up as the "wave" in vertical scrolling. */
    private static Bitmap decodeStream(InputStream is, int maxDimPx) {
        // Always read fully into a byte array first: purgeable decoding
        // needs the source bytes retained, and the cap stops a corrupt or
        // huge response before the decoder sees it.
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
            if (NotPipe.SDK < 11) {
                // Bitmap pixels off the VM heap: the VM frees them under
                // pressure and re-decodes on demand instead of freezing
                // the UI thread with a collection mid-fling.
                opts.inPurgeable = true;
                opts.inInputShareable = true;
            }
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (OutOfMemoryError e) {
            Log.e("ImageLoader", "OOM decoding image");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static class ImageLoadRunnable implements Runnable {
        private final String url;
        private final boolean useCache;
        private final int maxDimPx;

        ImageLoadRunnable(String url, boolean useCache) {
            this(url, useCache, 0);
        }

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
            final Bitmap finalBmp = bmp;
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    List<ImageRequest> pendingList;
                    synchronized (ImageLoader.class) {
                        pendingList = new ArrayList<ImageRequest>(pendingViews.get(url));
                        pendingViews.remove(url);
                    }

                    if (finalBmp != null) {
                        if (useCache) {
                            if (cacheHistory.contains(url)) {
                                cacheHistory.remove(url);
                            }
                            cacheHistory.addLast(url);
                            memoryCache.put(url, finalBmp);

                            if (cacheHistory.size() > MAX_CACHE_SIZE) {
                                String oldest = cacheHistory.removeFirst();
                                memoryCache.remove(oldest);
                            }
                        }

                        for (ImageRequest req : pendingList) {
                            if (req.view != null) {
                                String currentTag = (String) req.view.getTag();
                                if (url.equals(currentTag)) {
                                    req.view.setImageBitmap(finalBmp);
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
    }

    /**
     * Clear the memory cache to free memory.
     */
    public static void clearCache() {
        memoryCache.clear();
        cacheHistory.clear();
        System.gc();
    }
}