package io.github.gohoski.notpipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import io.github.gohoski.notpipe.api.Conversion;
import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.api.VideoStream;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.SubtitleTrack;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.api.ContentUnavailableException;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;
import io.github.gohoski.notpipe.ui.AdapterLinearLayout;
import io.github.gohoski.notpipe.ui.AspectRatioVideoView;
import io.github.gohoski.notpipe.ui.CommentAdapter;
import io.github.gohoski.notpipe.ui.VideoAdapter;
import io.github.gohoski.notpipe.util.ChannelIconResolver;
import io.github.gohoski.notpipe.util.ConnectivityMonitor;
import io.github.gohoski.notpipe.util.ImageLoader;
import io.github.gohoski.notpipe.util.VttSubtitles;

public class VideoActivity extends Activity implements ConnectivityMonitor.Listener {
    String videoId;
    LinearLayout videoLayout;
    FrameLayout videoFrame;
    Context context;
    ImageView thumbnail, channelThumbnail, play;
    VideoView videoView;
    View relatedList, commentsList;
    ProgressBar relatedLoading, commentsLoading;
    ScrollView scrollView;
    View tabsScrollView;

    Video video;
    List<VideoInfo> relatedVideos = new ArrayList<VideoInfo>();
    List<Comment> comments = new ArrayList<Comment>();
    VideoAdapter relatedAdapter;
    CommentAdapter commentsAdapter;

    Metadata api;
    VideoStream videoStream;
    Config config;

    boolean relatedLoaded = false;
    boolean commentsLoaded = false;
    protected boolean isOpencore = NotPipe.SDK < 8; // OpenCORE (Android <2.2) playback bugs need workarounds

    private LoadVideoTask loadVideoTask;
    private ResolveStreamTask resolveStreamTask;
    private DownloadVideoTask downloadVideoTask;

    private static final int MAX_STREAM_RETRIES = 3;
    private int streamRetryCount = 0;
    private LoadCommentsTask loadCommentsTask;
    private LoadRelatedTask loadRelatedTask;

    // Retry Handler and Runnable to manage automated recovery on I/O errors (1, -1004)
    private Handler retryHandler = new Handler();
    private Runnable retryRunnable = null;

    private int videoPosition = 0;
    private boolean videoPlaying = false;
    private boolean videoPrepared = false;
    private String videoUrl = null;
    private boolean isUsingMetadataUrl = false;
    private boolean isTabletFullscreen = false;
    private boolean isFullscreenMode = false; // full-screen state active
    private boolean isVideoViewNeedsReload = true; // surface lost: reload on resume
    private String resolvedQuality = null;
    private boolean isActivityStopped = false;

    private int videoBufferTimeout = 60000;
    private static final String DIR_VIDEOS = "notPipe/videos";

    // ------------------------------------------------------------------
    // Subtitles (WebVTT overlay on top of the VideoView; the ticker only
    // reads the player position every 300 ms)
    // ------------------------------------------------------------------
    private VttSubtitles subtitles;
    private List<SubtitleTrack> subtitleTracks;
    private SubtitleTrack activeSubtitleTrack;
    private TextView subtitleView;
    private String lastSubtitleText;
    private Handler subtitleHandler = new Handler();
    private boolean subtitlesTaskRunning = false;
    private final Runnable subtitleTick = new Runnable() {
        @Override
        public void run() {
            if (subtitles == null || isActivityStopped) return;
            try {
                if (videoView != null && videoView.getVisibility() == View.VISIBLE && videoPlaying) {
                    String text = subtitles.getTextAt(videoView.getCurrentPosition());
                    if (text == null && lastSubtitleText == null) {
                        // Nothing showing and nothing to hide: cheapest path.
                    } else if (text == null) {
                        lastSubtitleText = null;
                        if (subtitleView != null) subtitleView.setVisibility(View.GONE);
                    } else if (!text.equals(lastSubtitleText)) {
                        lastSubtitleText = text;
                        ensureSubtitleView();
                        subtitleView.setText(text);
                        subtitleView.setVisibility(View.VISIBLE);
                    }
                } else if (lastSubtitleText != null) {
                    lastSubtitleText = null;
                    if (subtitleView != null) subtitleView.setVisibility(View.GONE);
                }
            } catch (Throwable ignored) {
                // the player can be mid-release during a rotation
            }
            subtitleHandler.postDelayed(this, 300);
        }
    };

    private Handler systemUiHandler = new Handler();
    private Runnable hideSystemUiRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTabletFullscreen || isFullscreenMode) {
                hideSystemUI();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);

        api = Manager.getInstance().getMetadata();
        config = ConfigManager.getInstance().getConfig();
        context = this;

        Intent intent = getIntent();
        Uri uri = intent.getData();
        if (uri == null) {
            videoId = intent.getStringExtra("ID");
        } else {
            videoId = uri.getHost().contains("youtube.com") ? uri.getQueryParameter("v") : uri.getLastPathSegment();
        }

        relatedAdapter = new VideoAdapter(this, R.layout.item_video, relatedVideos);
        relatedAdapter.setChannelIconListener(new VideoAdapter.ChannelIconListener() {
            @Override
            public String getResolvedIcon(String channelId) {
                return ChannelIconResolver.getResolved(channelId);
            }

            @Override
            public void onRequestFallbackIcon(String channelId) {
                ChannelIconResolver.requestFallback(channelId, new ChannelIconResolver.OnIconResolvedListener() {
                    @Override
                    public void onIconResolved(String channelId, String url) {
                        if (url != null && url.length() > 0 && relatedLoaded && relatedList instanceof ViewGroup) {
                            // persist: heals the feed and subscriptions too
                            Subscriptions.setAvatar(channelId, url);
                            ViewGroup vg = (ViewGroup) relatedList;
                            for (int i = 0; i < vg.getChildCount(); i++) {
                                View child = vg.getChildAt(i);
                                if (child != null) {
                                    relatedAdapter.updateChannelIconForView(child, channelId);
                                }
                            }
                        }
                    }
                });
            }
        });

        commentsAdapter = new CommentAdapter(this, R.layout.item_comment, comments);
        // NewPipe-style inline replies (see toggleCommentReplies)
        commentsAdapter.setOnRepliesToggleListener(new CommentAdapter.OnRepliesToggleListener() {
            public void onToggleReplies(Comment comment) {
                toggleCommentReplies(comment);
            }
        });

        setupViewReferences();
        setupAdapters();
        setupTabHost();
        // Hide the tabs panel until the main video loads
        View tabHost = findViewById(android.R.id.tabhost);
        if (tabHost != null) {
            tabHost.setVisibility(View.GONE);
        }

        // Apply orientation configuration
        if (isTablet()) {
            applyTabletLayout(getResources().getConfiguration().orientation);
        } else {
            View fsButton = findViewById(R.id.full_screen);
            if (fsButton != null) fsButton.setVisibility(View.GONE);
        }

        videoView.setVisibility(View.GONE);
        applyOpenCoreLayoutFix();
        setupClickListeners();
        setupScrollHandler();

        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE && !isTablet()) {
            enterFullscreenMode();
        }

        if (VideoCache.hasValidMetadata(videoId)) {
            api = VideoCache.getMetadataInstance();
            onVideoDataLoaded(VideoCache.getVideo());
        } else {
            loadVideoTask = new LoadVideoTask();
            loadVideoTask.execute(videoId);
        }
    }

    private void handleVideoClick(int position) {
        ImageLoader.clearCache();
        System.gc();
        Intent intent = new Intent(context, VideoActivity.class);
        intent.putExtra("ID", relatedVideos.get(position).id);
        context.startActivity(intent);
    }

    private int lastScrollY = -1;
    private int lastTabsScrollY = -1;
    private int lastListHeight = -1;
    private Handler scrollHandler = new Handler();
    private Runnable scrollCheckRunnable = new Runnable() {
        @Override
        public void run() {
            boolean shouldCheck = false;
            if (scrollView != null) {
                int currentScrollY = scrollView.getScrollY();
                if (currentScrollY != lastScrollY || lastScrollY == -1) {
                    lastScrollY = currentScrollY;
                    shouldCheck = true;
                }
            }
            if (tabsScrollView != null) {
                int currentTabsScrollY = tabsScrollView.getScrollY();
                if (currentTabsScrollY != lastTabsScrollY || lastTabsScrollY == -1) {
                    lastTabsScrollY = currentTabsScrollY;
                    shouldCheck = true;
                }
            }
            int currentListHeight = 0;
            TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
            if (tabHost != null) {
                String currentTab = tabHost.getCurrentTabTag();
                View activeView = null;
                if ("related".equals(currentTab) && relatedLoaded) {
                    activeView = relatedList;
                } else if ("comments".equals(currentTab) && commentsLoaded) {
                    activeView = commentsList;
                }
                if (activeView != null) currentListHeight = activeView.getHeight();
            }
            if (currentListHeight != lastListHeight || lastListHeight == -1) {
                lastListHeight = currentListHeight;
                shouldCheck = true;
            }
            if (shouldCheck) checkVisibleItems();
            scrollHandler.postDelayed(this, 100);
        }
    };

    /** Loads the images of the rows currently on screen. */
    private void checkVisibleItems() {
        TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        if (tabHost == null) return;

        String currentTab = tabHost.getCurrentTabTag();
        View activeView = null;

        if ("related".equals(currentTab) && relatedLoaded) {
            activeView = relatedList;
        } else if ("comments".equals(currentTab) && commentsLoaded) {
            activeView = commentsList;
        }

        if (activeView instanceof AdapterLinearLayout) {
            AdapterLinearLayout activeList = (AdapterLinearLayout) activeView;
            if (activeList.getChildCount() == 0) return;

            // Outermost ScrollView ancestor: scroll_view in portrait,
            // tabs_scroll_view in landscape.
            ScrollView activeScrollView = null;
            ViewParent parent = activeList.getParent();
            while (parent != null) {
                if (parent instanceof ScrollView) {
                    activeScrollView = (ScrollView) parent;
                }
                parent = parent.getParent();
            }

            if (activeScrollView == null || activeScrollView.getHeight() == 0) return;

            int visibleTop = activeScrollView.getScrollY();
            int visibleBottom = visibleTop + activeScrollView.getHeight();

            for (int i = 0; i < activeList.getChildCount(); i++) {
                View child = activeList.getChildAt(i);
                // Skip not-yet-laid-out children
                if (child == null || child.getHeight() == 0) continue;

                // child top relative to the active ScrollView
                int childTop = 0;
                View curr = child;
                while (curr != null && curr != activeScrollView) {
                    childTop += curr.getTop();
                    ViewParent currParent = curr.getParent();
                    if (currParent instanceof View) {
                        curr = (View) currParent;
                    } else {
                        curr = null;
                    }
                }

                int childBottom = childTop + child.getHeight();

                if (childBottom > visibleTop && childTop < visibleBottom) {
                    if ("related".equals(currentTab)) {
                        relatedAdapter.loadImagesForView(child);
                    } else {
                        commentsAdapter.loadImagesForView(child);
                    }
                }
            }
        }
    }

    private static final int POSITION_TRACK_INTERVAL = 5000;
    private Handler positionTrackHandler = new Handler();
    private Runnable positionTrackRunnable = new Runnable() {
        @Override
        public void run() {
            if (videoView != null && videoPrepared) {
                try {
                    if (videoView.isPlaying()) {
                        int pos = videoView.getCurrentPosition();
                        if (pos > 0) {
                            videoPosition = pos;
                        }
                    }
                } catch (Exception ignored) {}
            }
            positionTrackHandler.postDelayed(this, POSITION_TRACK_INTERVAL);
        }
    };

    private void startPositionTracking() {
        positionTrackHandler.removeCallbacks(positionTrackRunnable);
        positionTrackHandler.postDelayed(positionTrackRunnable, POSITION_TRACK_INTERVAL);
    }

    private void stopPositionTracking() {
        positionTrackHandler.removeCallbacks(positionTrackRunnable);
    }

    /** Buffer timeout matching the requested quality and video length. */
    private int getTimeoutForQuality(String quality) {
        if (video == null || "360".equals(quality)) {
            return 60000;
        } else {
            if (video.length < 1800) { // Shorter than 30 minutes
                return 180000;
            } else { // 30 minutes to 60 minutes
                return 360000;
            }
        }
    }
    private String determineQuality() {
        String quality = config.getPreferredQuality();
        if (quality == null) quality = "360";

        if (video == null) {
            videoBufferTimeout = 60000;
            return quality;
        }

        if (!"360".equals(quality)) {
            if (video.length > 3600) {
                quality = "360";
                Toast.makeText(context, R.string.long_360, Toast.LENGTH_LONG).show();
            } else if ("1080".equals(quality)) {
                Toast.makeText(context, R.string.experimental_1080, Toast.LENGTH_LONG).show();
            }
        }

        videoBufferTimeout = getTimeoutForQuality(quality);
        return quality;
    }

    /** Shows the "Processing %sp..." label when playback starts. */
    private void updateProgressMessage(String quality) {
        TextView progressView = (TextView) findViewById(R.id.video_progress);
        if (progressView != null) {
            if (quality == null) quality = "360";
            if (config.isConvertVideos()) {
                progressView.setText(R.string.conv_long);
                progressView.setVisibility(View.VISIBLE);
            } else if (!"360".equals(quality)) {
                progressView.setText(getString(R.string.hq_long, quality));
                progressView.setVisibility(View.VISIBLE);
            } else {
                progressView.setVisibility(View.GONE);
            }
        }
    }

    private void resetVideo() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cancelVideoTimeout();
        stopPositionTracking();
        if (videoView != null) {
            videoView.stopPlayback();
            videoView.setVideoURI(null);
            videoView.setMediaController(null);
        }
        videoPlaying = false;
        videoPrepared = false;
        videoUrl = null;
    }

    /** Stores the current playback position so a restart can resume there. */
    private void rememberPosition() {
        if (videoView == null) return;
        try {
            if (videoPrepared) {
                int pos = videoView.getCurrentPosition();
                if (pos > 0) videoPosition = pos;
            }
        } catch (Exception ignored) {}
    }

    /** Declares the source dead (except local files) and retries the
     *  stream, or gives up with a message after MAX_STREAM_RETRIES. */
    private void handleStreamFailure(boolean keepInstance, String giveUpMessage) {
        resetVideo();
        boolean isOffline = !Utils.hasConnection(context);
        if (!keepInstance) {
            Object dead = isUsingMetadataUrl ? api : videoStream;
            if (dead != null && !isOffline) {
                Manager.getInstance().removeDeadInstance(dead);
            }
        }
        isUsingMetadataUrl = false;
        if (isOffline || streamRetryCount < MAX_STREAM_RETRIES) {
            if (!isOffline) streamRetryCount++;
            showVideoLoadingUI();
            resolveStreamTask = new ResolveStreamTask(null);
            resolveStreamTask.execute(videoId);
        } else {
            restoreVideoUI();
            Toast.makeText(context, giveUpMessage, Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreVideoUI() {
        LinearLayout loading = (LinearLayout) findViewById(R.id.video_loading);
        if (loading != null) loading.setVisibility(View.GONE);
        if (play != null) play.setVisibility(View.VISIBLE);
        if (thumbnail != null) thumbnail.setVisibility(View.VISIBLE);
        TextView progressView = (TextView) findViewById(R.id.video_progress);
        if (progressView != null) progressView.setVisibility(View.GONE);
    }

    private void showVideoLoadingUI() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View loading = findViewById(R.id.video_loading);
        if (loading != null) loading.setVisibility(View.VISIBLE);
        if (play != null) play.setVisibility(View.GONE);
        if (thumbnail != null) thumbnail.setVisibility(View.INVISIBLE); // INVISIBLE keeps the bounding box
    }

    private VideoStream selectVideoStream(String quality) {
        List<VideoStream> targetList = "360".equals(quality)
                ? Manager.getInstance().getVideoInstances()
                : Manager.getInstance().getHqInstances();

        if (config.isConvertVideos()) {
            List<Conversion> conversions = Manager.getInstance().getConversion();
            List<VideoStream> convInstances = new ArrayList<VideoStream>();
            for (int i = 0; i < targetList.size(); i++) {
                VideoStream vs = targetList.get(i);
                if (vs instanceof Conversion && conversions.contains(vs)) {
                    convInstances.add(vs);
                }
            }
            if (!convInstances.isEmpty()) {
                targetList = convInstances;
            }
        }

        if (!targetList.isEmpty()) {
            return targetList.get(new Random().nextInt(targetList.size()));
        }
        return null;
    }

    /** Only HQ-list instances (yt2009/YtApiLegacy) can serve 480p+;
     *  Piped/Invidious ignore the quality argument and return 360p. */
    private boolean instanceServesQuality(VideoStream instance, String quality) {
        if (instance == null) return false;
        if (quality == null || "360".equals(quality)) return true;
        return Manager.getInstance().getHqInstances().contains(instance);
    }

    /** Re-picks the instance from the right pool when it cannot serve the quality. */
    private void ensureInstanceForQuality(String quality) {
        if (quality == null) quality = "360";
        if (instanceServesQuality(videoStream, quality)) return;
        videoStream = selectVideoStream(quality);
    }

    private void playVideo() {
        if (video == null) return;
        streamRetryCount = 0;
        showVideoLoadingUI();
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                final String runQuality = resolvedQuality != null ? resolvedQuality : "360";
                updateProgressMessage(runQuality);
                if (VideoCache.hasValidStream(videoId, runQuality)) {
                    videoUrl = VideoCache.getStreamUrl();
                    videoStream = VideoCache.getVideoStreamInstance();
                    proceedPlay(videoUrl);
                    return;
                }
                if (config.isConvertVideos() || !"360".equals(runQuality) || video.videoUrl == null || video.videoUrl.length() == 0) {
                    isUsingMetadataUrl = false;
                    ensureInstanceForQuality(runQuality);
                    resolveStreamTask = new ResolveStreamTask(videoStream, runQuality);
                    resolveStreamTask.execute(videoId, runQuality);
                } else {
                    isUsingMetadataUrl = true;
                    videoUrl = video.videoUrl;
                    VideoCache.putStream(videoId, videoUrl, "360", null);
                    proceedPlay(videoUrl);
                }
            }
        });
    }

    private boolean hasSoftwareKeys() {
        if (NotPipe.SDK < 14) return false;
        try {
            // Physical Menu/Back keys mean no on-screen nav bar (Samsung
            // tablet black-bar glitch fix).
            android.view.ViewConfiguration viewConfig = android.view.ViewConfiguration.get(this);
            boolean hasMenuKey = ((Boolean) viewConfig.getClass().getMethod("hasPermanentMenuKey").invoke(viewConfig)).booleanValue();
            boolean hasBackKey = android.view.KeyCharacterMap.deviceHasKey(android.view.KeyEvent.KEYCODE_BACK);
            return !hasMenuKey && !hasBackKey;
        } catch (Exception e) {
            return true;
        }
    }

    private void hideSystemUI() {
        try {
            View decorView = getWindow().getDecorView();
            if (NotPipe.SDK >= 14) {
                int flags = 1 | 4; // SYSTEM_UI_FLAG_LOW_PROFILE | SYSTEM_UI_FLAG_FULLSCREEN
                if (hasSoftwareKeys()) {
                    flags |= 2; // SYSTEM_UI_FLAG_HIDE_NAVIGATION
                }
                if (NotPipe.SDK >= 19) {
                    flags |= 4096; // SYSTEM_UI_FLAG_IMMERSIVE_STICKY (Better than standard IMMERSIVE)
                }
                decorView.getClass().getMethod("setSystemUiVisibility", int.class).invoke(decorView, flags);
            } else if (NotPipe.SDK >= 11) {
                decorView.getClass().getMethod("setSystemUiVisibility", int.class).invoke(decorView, 1);
            }
        } catch (Exception ignored) {}
    }

    private void showSystemUI() {
        try {
            View decorView = getWindow().getDecorView();
            if (NotPipe.SDK >= 11) {
                decorView.getClass().getMethod("setSystemUiVisibility", int.class).invoke(decorView, 0);
            }
        } catch (Exception ignored) {}
    }

    private void hideDummyTab() {
        final TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        if (tabHost != null && tabHost.getTabWidget() != null && tabHost.getTabWidget().getChildCount() > 0) {
            tabHost.getTabWidget().post(new Runnable() {
                @Override
                public void run() {
                    View dummyTab = tabHost.getTabWidget().getChildAt(0);
                    dummyTab.setVisibility(View.GONE);
                    ViewGroup.LayoutParams params = dummyTab.getLayoutParams();
                    if (params != null) {
                        params.width = 0;
                        params.height = 0;
                        dummyTab.setLayoutParams(params);
                    }
                }
            });
        }
    }

    private void applyOpenCoreLayoutFix() {
        if (isOpencore && videoView != null) {
            videoView.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.FILL_PARENT
            );
            params.gravity = android.view.Gravity.CENTER;
            videoView.setLayoutParams(params);
            videoView.requestLayout();
        }
    }

    private File getCachedVideoFile(String vId) {
        File sdCard = Environment.getExternalStorageDirectory();
        File dir = new File(sdCard, DIR_VIDEOS);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, vId + ".mp4");
    }

    private void updatePlaybackViaText(String host) {
        TextView playbackInfo = (TextView) findViewById(R.id.playback_info);
        if (playbackInfo != null) playbackInfo.setText(getString(R.string.playback_via, host));
    }

    private void attachVideoListeners() {
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(final MediaPlayer mp) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cancelVideoTimeout();
                        streamRetryCount = 0;
                        restoreVideoUI();
                        thumbnail.setVisibility(View.INVISIBLE);
                        play.setVisibility(View.GONE);

                        videoPrepared = true;
                        if (videoPosition > 0) {
                            videoView.seekTo(videoPosition);
                        }

                        AspectRatioVideoView arvv = (AspectRatioVideoView) videoView;
                        arvv.setVideoDimensions(mp.getVideoWidth(), mp.getVideoHeight());

                        MediaController mc = new MediaController(context);
                        mc.setAnchorView(videoFrame);
                        videoView.setMediaController(mc);

                        if (isOpencore) {
                            videoView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    ((AspectRatioVideoView) videoView).forceLayoutUpdate();
                                    videoView.postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (videoPlaying) {
                                                mp.start(); startPositionTracking();
                                            }
                                        }
                                    }, 200);
                                }
                            }, 100);
                        } else {
                            if (videoPlaying) {
                                mp.start(); startPositionTracking();
                            }
                        }
                    }
                });
            }
        });

        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                cancelVideoTimeout();
                videoPlaying = false;
                videoPosition = 0;
            }
        });

        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, final int what, final int extra) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        cancelVideoTimeout();
                        if (what == 43 || what == -11) return; // Ignore certain framework bugs

                        // Error code indicates unsupported format
                        if (what == 1 && extra == -2147483648) {
                            resetVideo(); restoreVideoUI();
                            Toast.makeText(context, R.string.unsupported_format, Toast.LENGTH_LONG).show();
                            return;
                        }

                        // Local cached file?
                        boolean isLocalFile = false;
                        if (videoUrl != null && (videoUrl.startsWith(Environment.getExternalStorageDirectory().getPath()) || videoUrl.startsWith("file://"))) {
                            isLocalFile = true;
                        }

                        if (isLocalFile) {
                            // Corrupted download or aborted conversion: delete and retry.
                            try {
                                File cachedFile = getCachedVideoFile(videoId);
                                if (cachedFile.exists()) {
                                    cachedFile.delete();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            VideoCache.clearAll(); // forget the corrupted path
                            resetVideo();

                            showVideoLoadingUI();
                            resolveStreamTask = new ResolveStreamTask(null);
                            resolveStreamTask.execute(videoId);
                            return;
                        }

                        // Buffer drop on the network stream (MEDIA_ERROR_IO)
                        if (what == 1 && extra == -1004) {
                            final String savedUrl = videoUrl;
                            if (videoView != null) {
                                try {
                                    int pos = videoView.getCurrentPosition();
                                    if (pos > 0) {
                                        videoPosition = pos;
                                    }
                                } catch (Exception e) { e.printStackTrace(); }
                            }
                            resetVideo();
                            videoUrl = savedUrl; // resetVideo() clears it

                            showVideoLoadingUI();

                            if (retryRunnable != null) {
                                retryHandler.removeCallbacks(retryRunnable);
                            }

                            retryRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    if (isActivityStopped) {
                                        isVideoViewNeedsReload = true;
                                        return;
                                    }
                                    if (videoUrl != null) {
                                        applyOpenCoreLayoutFix();
                                        videoView.setVisibility(View.VISIBLE);
                                        attachVideoListeners();
                                        videoPlaying = true;
                                        loadVideoUri(videoUrl);
                                    } else {
                                        resolveStreamTask = new ResolveStreamTask(null);
                                        resolveStreamTask.execute(videoId);
                                    }
                                }
                            };
                            retryHandler.postDelayed(retryRunnable, 2999);
                            return;
                        }

                        handleStreamFailure(isLocalFile, "Stream failed after multiple attempts.");
                    }
                });
                return true;
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("videoId", videoId);

        // Save position and playing state
        if (videoView != null && videoPrepared) {
            try {
                rememberPosition();
                if (videoView.isPlaying()) {
                    videoPlaying = true;
                }
            } catch (Exception ignored) {}
        }

        outState.putInt("videoPosition", videoPosition);
        outState.putBoolean("videoPlaying", videoPlaying);
        outState.putBoolean("videoPrepared", videoPrepared);
        outState.putBoolean("isUsingMetadataUrl", isUsingMetadataUrl);
        outState.putBoolean("isTabletFullscreen", isTabletFullscreen);
        outState.putBoolean("isFullscreenMode", isFullscreenMode);
        if (video != null) {
            outState.putString("videoUrl", videoUrl);
            outState.putString("title", video.title);
            outState.putString("channel", video.channel);
            outState.putString("channelThumbnail", video.channelThumbnail);
            outState.putString("thumbnail", video.thumbnail);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        videoId = savedInstanceState.getString("videoId");
        videoPosition = savedInstanceState.getInt("videoPosition");
        videoPlaying = savedInstanceState.getBoolean("videoPlaying");
        videoPrepared = savedInstanceState.getBoolean("videoPrepared");
        isUsingMetadataUrl = savedInstanceState.getBoolean("isUsingMetadataUrl", false);
        videoUrl = savedInstanceState.getString("videoUrl");
        isTabletFullscreen = savedInstanceState.getBoolean("isTabletFullscreen", false);
        isFullscreenMode = savedInstanceState.getBoolean("isFullscreenMode", false);

        if (isFullscreenMode) {
            enterFullscreenMode();
        } else if (isTabletFullscreen && isTablet()) {
            enterFullscreenMode();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            if (isFullscreenMode) {
                if (isTablet()) {
                    exitFullscreenMode();
                    return true;
                } else {
                    // Phone logic
                    int currentOrientation = getResources().getConfiguration().orientation;
                    if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                        return true;
                    } else {
                        exitFullscreenMode();
                        return true;
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        systemUiHandler.removeCallbacks(hideSystemUiRunnable);
        scrollHandler.removeCallbacks(scrollCheckRunnable);
        if (retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
        cancelVideoTimeout();
        stopPositionTracking();
        ImageLoader.clearCache();

        // Cancel in-flight tasks
        if (loadVideoTask != null) loadVideoTask.cancel(true);
        if (resolveStreamTask != null) resolveStreamTask.cancel(true);
        if (downloadVideoTask != null) downloadVideoTask.cancel(true);
        if (loadCommentsTask != null) loadCommentsTask.cancel(true);
        if (loadRelatedTask != null) loadRelatedTask.cancel(true);
    }

    /** ConnectivityMonitor: the network was down and is back. When the
     *  video metadata never arrived (offline error) the page is retried;
     *  a page that already plays or shows its info is left untouched. */
    public void onConnectionRestored() {
        if (isActivityStopped || isFinishing()) return;
        if (video != null) return; // metadata already on screen
        if (loadVideoTask != null
                && loadVideoTask.getStatus() != AsyncTask.Status.FINISHED) {
            return; // still running or queued: let it finish
        }
        View loading = findViewById(R.id.loading);
        if (loading != null) loading.setVisibility(View.VISIBLE);
        loadVideoTask = new LoadVideoTask();
        loadVideoTask.execute(videoId);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConnectivityMonitor.unregister(this);
        stopPositionTracking();
        // No subtitle ticks while not in the foreground.
        stopSubtitleTicker();
        try {
            if (videoView != null && videoPrepared) {
                rememberPosition();
                if (videoView.isPlaying()) {
                    videoPlaying = true;
                    videoView.pause();
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onStart() {
        super.onStart();
        isActivityStopped = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        isActivityStopped = true;
        cancelVideoTimeout(); // no timeouts in the background

        if (videoView != null && videoUrl != null) {
            videoView.stopPlayback();
            videoView.setVisibility(View.GONE);
            isVideoViewNeedsReload = true;
            videoPrepared = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reconnect watcher: retries a failed metadata load once the
        // network is back while this screen is the visible one.
        ConnectivityMonitor.register(this);
        // Resume the subtitle ticker stopped in onPause.
        if (subtitles != null) {
            startSubtitleTicker();
        }
        if (videoUrl != null && !config.isUseExternalPlayer()) {
            if (config.isStreamPlayback() || videoUrl.startsWith(Environment.getExternalStorageDirectory().getPath()) || videoUrl.startsWith("file://")) {
                if (isVideoViewNeedsReload) {
                    showVideoLoadingUI();

                    updateProgressMessage(resolvedQuality);
                    applyOpenCoreLayoutFix();
                    videoView.setVisibility(View.VISIBLE);
                    attachVideoListeners();
                    loadVideoUri(videoUrl);
                    isVideoViewNeedsReload = false;
                }
            }
        }
        if (relatedLoaded) {
            ViewGroup relatedViewGroup = (ViewGroup) relatedList;
            for (int i = 0; i < relatedViewGroup.getChildCount(); i++) {
                relatedAdapter.resetImageView(relatedViewGroup.getChildAt(i));
            }
            checkVisibleItems();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
        VideoCache.clearAll();
    }

    private boolean isTablet() {
        return getResources().getBoolean(R.bool.is_tablet);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Release the sensor lock back in portrait
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && !isFullscreenMode) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }

        if (isTablet()) {
            applyTabletLayout(newConfig.orientation);

            // Tablets stay in fullscreen on rotation if manually toggled
            if (isFullscreenMode) {
                enterFullscreenMode();
            } else {
                if (videoView != null) {
                    videoView.requestLayout();
                    videoView.invalidate();
                    ((AspectRatioVideoView) videoView).forceLayoutUpdate();
                }
            }
        } else {
            // For phones:
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                enterFullscreenMode();
            } else {
                // Stay fullscreen if it was toggled without forced landscape
                if (isFullscreenMode && !config.isFullscreenRotateLandscape()) {
                    enterFullscreenMode();
                } else {
                    exitFullscreenMode();
                }
            }
        }
    }

    private void applyTabletLayout(int orientation) {
        LinearLayout mainContentLayout = (LinearLayout) findViewById(R.id.main_content_layout);
        View videoLayout = findViewById(R.id.video_layout);
        ViewGroup portraitTabContainer = (ViewGroup) findViewById(R.id.portrait_tab_container);
        ViewGroup landscapeTabContainer = (ViewGroup) findViewById(R.id.landscape_tab_container);
        TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        View tabsScrollView = findViewById(R.id.tabs_scroll_view);
        View fsButton = findViewById(R.id.full_screen);

        if (mainContentLayout == null || videoLayout == null || portraitTabContainer == null
                || landscapeTabContainer == null || tabHost == null) {
            return;
        }

        if (fsButton != null) {
            // Tablet landscape only, hidden while fullscreen
            if (orientation == Configuration.ORIENTATION_LANDSCAPE && !isFullscreenMode) {
                fsButton.setVisibility(View.VISIBLE);
            } else {
                fsButton.setVisibility(View.GONE);
            }
        }

        // Detach the TabHost from its current parent
        ViewGroup currentParent = (ViewGroup) tabHost.getParent();
        if (currentParent != null) {
            currentParent.removeView(tabHost);
        }

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mainContentLayout.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.FILL_PARENT,
                    6.0f
            );
            videoLayout.setLayoutParams(videoParams);

            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.FILL_PARENT,
                    3.5f
            );
            landscapeTabContainer.setLayoutParams(tabParams);

            landscapeTabContainer.setVisibility(isFullscreenMode ? View.GONE : View.VISIBLE);
            portraitTabContainer.setVisibility(View.GONE);

            // The video column keeps the classic arrangement: video on
            // top, channel info + like/share buttons below it; only the
            // tabs move to the right column.
            landscapeTabContainer.addView(tabHost, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.FILL_PARENT
            ));

            if (tabsScrollView != null) {
                tabsScrollView.getLayoutParams().height = ViewGroup.LayoutParams.FILL_PARENT;
                tabsScrollView.requestLayout();
            }
        } else {
            mainContentLayout.setOrientation(LinearLayout.VERTICAL);

            LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.FILL_PARENT,
                    1.0f
            );
            videoLayout.setLayoutParams(videoParams);

            landscapeTabContainer.setVisibility(View.GONE);
            portraitTabContainer.setVisibility(isFullscreenMode ? View.GONE : View.VISIBLE);

            portraitTabContainer.addView(tabHost, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            if (tabsScrollView != null) {
                tabsScrollView.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                tabsScrollView.requestLayout();
            }
        }

        hideDummyTab();
        triggerLayoutFix(tabHost);
    }

    private void toggleActionBar(boolean show) {
        if (NotPipe.SDK >= 11) {
            try {
                Object actionBar = Activity.class.getMethod("getActionBar").invoke(this);
                if (actionBar != null) {
                    actionBar.getClass().getMethod(show ? "show" : "hide").invoke(actionBar);
                }
            } catch (Exception ignored) {}
        }
        try {
            View titleView = getWindow().findViewById(android.R.id.title);
            if (titleView != null) {
                titleView.setVisibility(show ? View.VISIBLE : View.GONE);
                if (titleView.getParent() instanceof View) {
                    ((View) titleView.getParent()).setVisibility(show ? View.VISIBLE : View.GONE);
                }
            }
        } catch (Exception ignored) {}
    }

    private void enterFullscreenMode() {
        isFullscreenMode = true;
        isTabletFullscreen = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        toggleActionBar(false);

        hideSystemUI();
        systemUiHandler.removeCallbacks(hideSystemUiRunnable);
        systemUiHandler.postDelayed(hideSystemUiRunnable, 5000);

        if (scrollView != null) scrollView.setVisibility(View.GONE);
        if (videoFrame != null) videoFrame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT, 1.0f));

        View tabHost = findViewById(android.R.id.tabhost);
        if (tabHost != null && tabHost.getParent() instanceof View) {
            ((View) tabHost.getParent()).setVisibility(View.GONE);
        }

        // (TabHost's parent is the tablet side container, so the parent
        // hide above collapses it too.)
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT);
        videoParams.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(videoParams);
        ((AspectRatioVideoView) videoView).setFullscreen(true);

        // After the action bar hides, snap the layout to the new bounds.
        if (NotPipe.SDK >= 11) {
            videoView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (videoLayout != null) {
                        videoLayout.requestLayout();
                        videoLayout.invalidate();
                    }
                    if (videoFrame != null) {
                        videoFrame.requestLayout();
                        videoFrame.invalidate();
                    }
                    videoView.requestLayout();
                    videoView.invalidate();
                    ((AspectRatioVideoView) videoView).forceLayoutUpdate();
                }
            }, 300);
        }
    }

    private void exitFullscreenMode() {
        isFullscreenMode = false;
        isTabletFullscreen = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        toggleActionBar(true);
        showSystemUI();
        systemUiHandler.removeCallbacks(hideSystemUiRunnable);
        if (scrollView != null) scrollView.setVisibility(View.VISIBLE);
        if (videoFrame != null) videoFrame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View tabHost = findViewById(android.R.id.tabhost);
        if (tabHost != null && tabHost.getParent() instanceof View) {
            ((View) tabHost.getParent()).setVisibility(View.VISIBLE);
        }
        if (isTablet()) {
            applyTabletLayout(getResources().getConfiguration().orientation);
        }
        FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.FILL_PARENT);
        videoParams.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(videoParams);
        Runnable updateLayout = new Runnable() {
            @Override
            public void run() {
                if (videoLayout != null) {
                    videoLayout.requestLayout();
                    videoLayout.invalidate();
                }
                videoView.requestLayout();
                videoView.invalidate();
                ((AspectRatioVideoView) videoView).forceLayoutUpdate();
            }
        };
        if (isOpencore) {
            videoView.post(updateLayout);
        } else {
            // Snap the bounds after the action bar expands.
            videoView.postDelayed(updateLayout, 300);
        }
        ((AspectRatioVideoView) videoView).setFullscreen(false);
        videoView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isActivityStopped) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        }, 350);
    }

    private void setupViewReferences() {
        videoView = (VideoView) findViewById(R.id.video);
        videoLayout = (LinearLayout) findViewById(R.id.video_layout);
        videoFrame = (FrameLayout) findViewById(R.id.video_frame);
        thumbnail = (ImageView) findViewById(R.id.thumbnail);
        channelThumbnail = (ImageView) findViewById(R.id.channel_thumbnail);
        play = (ImageView) findViewById(R.id.play);
        relatedList = findViewById(R.id.related_list);
        commentsList = findViewById(R.id.comments_list);
        relatedLoading = (ProgressBar) findViewById(R.id.related_loading);
        commentsLoading = (ProgressBar) findViewById(R.id.comments_loading);
        scrollView = (ScrollView) findViewById(R.id.scroll_view);
        tabsScrollView = findViewById(R.id.tabs_scroll_view);
    }

    private void setupAdapters() {
        if (relatedList instanceof android.widget.ListView) {
            android.widget.ListView lv = (android.widget.ListView) relatedList;
            lv.setAdapter(relatedAdapter);
            lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    handleVideoClick(position);
                }
            });
        } else if (relatedList instanceof AdapterLinearLayout) {
            AdapterLinearLayout all = (AdapterLinearLayout) relatedList;
            all.setAdapter(relatedAdapter);
            all.setOnItemClickListener(new AdapterLinearLayout.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterLinearLayout parent, View view, int position, long id) {
                    handleVideoClick(position);
                }
            });
        }

        if (commentsList instanceof android.widget.ListView) {
            ((android.widget.ListView) commentsList).setAdapter(commentsAdapter);
        } else if (commentsList instanceof AdapterLinearLayout) {
            ((AdapterLinearLayout) commentsList).setAdapter(commentsAdapter);
        }
    }

    private void setupClickListeners() {
        findViewById(R.id.share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(Intent.createChooser(
                            new Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, "https://youtu.be/" + videoId)
                                    .putExtra(android.content.Intent.EXTRA_SUBJECT, video != null ? video.title : ""),
                            getString(R.string.share)));
                } catch (android.content.ActivityNotFoundException ignored) {}
            }
        });

        TextView playbackInfo = (TextView) findViewById(R.id.playback_info);
        playbackInfo.setText(getString(R.string.playback_via, getString(R.string.loading_)));
        playbackInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Manager.InstanceInfo> instances = Manager.getInstance().videoInstancesInfo();
                if (instances.isEmpty()) return;
                final List<Object> dialogItems = new ArrayList<Object>();
                String preferredQuality = config.getPreferredQuality();
                String currentApiType = "";
                for (int i = 0; i < instances.size(); i++) {
                    Manager.InstanceInfo info = instances.get(i);
                    if (!info.name.equals(currentApiType)) {
                        currentApiType = info.name;
                        if (!info.supportsAllQualities && !"360".equals(preferredQuality)) {
                            dialogItems.add(currentApiType + " (360p)");
                        } else {
                            dialogItems.add(currentApiType);
                        }
                    }
                    dialogItems.add(info);
                }

                BaseAdapter adapter = new BaseAdapter() {
                    @Override
                    public int getCount() {
                        return dialogItems.size();
                    }

                    @Override
                    public Object getItem(int position) {
                        return dialogItems.get(position);
                    }

                    @Override
                    public long getItemId(int position) {
                        return position;
                    }

                    @Override
                    public boolean isEnabled(int position) {
                        return !(dialogItems.get(position) instanceof String);
                    }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        Object item = dialogItems.get(position);
                        TextView textView = new TextView(context);
                        int padding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12, getResources().getDisplayMetrics());
                        if (item instanceof String) {
                            textView.setText((String) item);
                            textView.setPadding(padding, padding, padding, padding / 2);
                            textView.setTypeface(null, Typeface.BOLD);
                            textView.setTextColor(Color.WHITE);
                            textView.setBackgroundColor(Color.DKGRAY);
                            textView.setTextSize(12);
                        } else {
                            Manager.InstanceInfo info = (Manager.InstanceInfo) item;
                            textView.setText(info.host);
                            textView.setPadding(padding * 2, padding, padding, padding);
                            textView.setTextSize(16);
                            if (NotPipe.SDK < 11) textView.setTextColor(Color.BLACK);
                        }
                        return textView;
                    }
                };

                new AlertDialog.Builder(context)
                        .setTitle(R.string.select_ins)
                        .setAdapter(adapter, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Object selected = dialogItems.get(which);
                                if (selected instanceof Manager.InstanceInfo) {
                                    Manager.InstanceInfo info = (Manager.InstanceInfo) selected;
                                    videoStream = info.instance;
                                    updatePlaybackViaText(info.host);

                                    if (!config.isUseExternalPlayer()) {
                                        thumbnail.setVisibility(View.INVISIBLE);
                                        play.setVisibility(View.GONE);
                                    }
                                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);

                                    if (videoView != null && videoPlaying && !config.isUseExternalPlayer()) {
                                        resetVideo();
                                    }
                                    findViewById(R.id.video_loading).setVisibility(View.VISIBLE);
                                    resolveStreamTask = new ResolveStreamTask(info.instance);
                                    resolveStreamTask.execute(videoId);
                                }
                            }
                        }).show();
            }
        });

        findViewById(R.id.video_meta).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (video == null) return;
                LinearLayout layout = new LinearLayout(context);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(15, 15, 15, 15);

                TextView viewsAndDate = new TextView(context);
                viewsAndDate.setText(getString(R.string.views, NumberFormat.getNumberInstance().format(video.views))
                        + "   " + DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(video.publishedAt));
                viewsAndDate.setTypeface(null, Typeface.BOLD);
                viewsAndDate.setPadding(0, 0, 0, 10);
                layout.addView(viewsAndDate);

                TextView desc = new TextView(context);
                desc.setText(video.description);
                desc.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                layout.addView(desc);

                ScrollView scroll = new ScrollView(context);
                scroll.addView(layout);

                new AlertDialog.Builder(context).setTitle(R.string.desc).setView(scroll).show();
            }
        });

        findViewById(R.id.channel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (video == null || video.channelId == null || video.channelId.length() == 0) return;
                Intent intent = new Intent(VideoActivity.this, ChannelActivity.class);
                intent.putExtra("ID", video.channelId);
                startActivity(intent);
                ImageLoader.clearCache();
                System.gc();
            }
        });

        View.OnClickListener playVideoListener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playVideo();
            }
        };
        if (thumbnail != null) thumbnail.setOnClickListener(playVideoListener);
        if (play != null) play.setOnClickListener(playVideoListener);

        View fullScreenBtn = findViewById(R.id.full_screen);
        if (fullScreenBtn != null) {
            fullScreenBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    enterFullscreenByConfig();
                }
            });
        }

        if (videoFrame != null) {
            videoFrame.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (isFullscreenMode && event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        showSystemUI();
                        systemUiHandler.removeCallbacks(hideSystemUiRunnable);
                        systemUiHandler.postDelayed(hideSystemUiRunnable, 5000);
                    }
                    return false;
                }
            });
        }
    }

    private void setupTabHost() {
        final TabHost tabHost = (TabHost) findViewById(android.R.id.tabhost);
        if (tabHost == null) return;
        if (tabHost.getTabWidget() != null && tabHost.getTabWidget().getChildCount() > 0) {
            tabHost.clearAllTabs();
        }
        tabHost.setup();
        // Holo randomly drops the first tab in tablet landscape: a hidden
        // dummy tab absorbs the bug.
        boolean hasDummy = false;
        if (isTablet() && NotPipe.SDK >= 11) {
            tabHost.addTab(tabHost.newTabSpec("dummy").setIndicator("").setContent(new TabHost.TabContentFactory() {
                public View createTabContent(String tag) {
                    return new View(VideoActivity.this);
                }
            }));
            hasDummy = true;
        }
        tabHost.addTab(tabHost.newTabSpec("related").setIndicator(getString(R.string.related)).setContent(R.id.related));
        tabHost.addTab(tabHost.newTabSpec("comments").setIndicator(getString(R.string.comments)).setContent(R.id.comments));

        TabWidget widget = tabHost.getTabWidget();
        if (widget != null) {
            if (isTablet() && NotPipe.SDK >= 11) {
                hideDummyTab();
            } else if (NotPipe.SDK < 11) {
                int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 33f, getResources().getDisplayMetrics());
                for (int i = 0; i < widget.getChildCount(); i++) {
                    View child = widget.getChildAt(i);
                    if (child != null && child.getLayoutParams() != null) {
                        child.getLayoutParams().height = height;
                    }
                }
            }
        }
        tabHost.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
            public void onTabChanged(final String tabId) {
                tabHost.post(new Runnable() {
                    @Override
                    public void run() {
                        if ("related".equals(tabId)) loadRelatedVideos();
                        else loadComments();
                    }
                });

                Runnable layoutFix = new Runnable() {
                    @Override
                    public void run() {
                        if (scrollView != null) {
                            scrollView.requestLayout();
                            scrollView.invalidate();
                        }
                        if (tabsScrollView != null) {
                            tabsScrollView.requestLayout();
                            tabsScrollView.invalidate();
                        }
                        checkVisibleItems();
                    }
                };
                if (tabsScrollView != null) {
                    tabsScrollView.post(layoutFix);
                } else if (scrollView != null) {
                    scrollView.post(layoutFix);
                }
            }
        });

        // With a dummy tab at 0, "related" is index 1
        if (hasDummy) tabHost.setCurrentTab(1);
    }

    private void setupScrollHandler() {
        scrollHandler.post(scrollCheckRunnable);
    }

    private android.os.Handler videoTimeoutHandler = new android.os.Handler();
    private Runnable videoTimeoutRunnable = null;

    private void setupVideoTimeout() {
        cancelVideoTimeout();
        videoTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                // runs on the UI thread
                handleStreamFailure(false, "Video stream timed out after multiple attempts.");
            }
        };
        videoTimeoutHandler.postDelayed(videoTimeoutRunnable, videoBufferTimeout);
    }

    private void cancelVideoTimeout() {
        if (videoTimeoutRunnable != null) {
            videoTimeoutHandler.removeCallbacks(videoTimeoutRunnable);
            videoTimeoutRunnable = null;
        }
    }

    private void loadRelatedVideos() {
        if (relatedLoaded || video == null) return;
        TextView noRelatedView = (TextView) findViewById(R.id.no_related);
        if (video.related != null && !video.related.isEmpty()) {
            relatedVideos.clear();
            relatedVideos.addAll(video.related);
            relatedAdapter.notifyDataSetChanged();
            relatedLoading.setVisibility(View.GONE);
            relatedLoaded = true;
            if (noRelatedView != null) noRelatedView.setVisibility(View.GONE);
            triggerLayoutFix(relatedList);
        } else {
            if (loadRelatedTask != null && loadRelatedTask.getStatus() != AsyncTask.Status.FINISHED) return;
            relatedLoading.setVisibility(View.VISIBLE);
            if (noRelatedView != null) noRelatedView.setVisibility(View.GONE);
            triggerLayoutFix(relatedLoading);
            loadRelatedTask = new LoadRelatedTask();
            loadRelatedTask.execute(videoId);
        }
    }

    private void loadComments() {
        if (commentsLoaded || video == null) return;
        if (video.comments != null && !video.comments.isEmpty()) {
            comments.clear();
            comments.addAll(video.comments);
            commentsAdapter.notifyDataSetChanged();
            commentsLoading.setVisibility(View.GONE);
            commentsLoaded = true;
            triggerLayoutFix(commentsList);
        } else {
            if (loadCommentsTask != null && loadCommentsTask.getStatus() != AsyncTask.Status.FINISHED) return;
            commentsLoading.setVisibility(View.VISIBLE);
            triggerLayoutFix(commentsLoading);
            loadCommentsTask = new LoadCommentsTask();
            loadCommentsTask.execute(videoId);
        }
    }

    /** Android 4.0-4.3 fix: setVideoURI off the UI thread (async player
     *  init setting), otherwise the MediaPlayer hangs the whole UI. */
    private void executeAsyncSetVideoUri(final String targetUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean needsFallback = false;
                try {
                    videoView.setVideoURI(Uri.parse(targetUrl));
                } catch (RuntimeException e) {
                    if (!e.getClass().getSimpleName().contains("CalledFromWrongThreadException")) {
                        needsFallback = true;
                    }
                } catch (Exception e) {
                    needsFallback = true;
                }

                if (needsFallback) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                videoView.setVideoURI(Uri.parse(targetUrl));
                            } catch (Exception e) { e.printStackTrace(); }
                            videoView.requestFocus(0);
                            setupVideoTimeout();
                            videoView.requestLayout();
                            videoView.invalidate();
                        }
                    });
                } else {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            videoView.requestFocus(0);
                            setupVideoTimeout();
                            videoView.requestLayout();
                            videoView.invalidate();
                        }
                    });
                }
            }
        }).start();
    }

    private void loadVideoUri(final String targetUrl) {
        if (config.isAsyncSetVideoUri()) {
            boolean surfaceReady = false;
            try {
                android.view.Surface surface = videoView.getHolder().getSurface();
                if (surface != null) {
                    if (NotPipe.SDK >= 9) {
                        surfaceReady = (Boolean) surface.getClass().getMethod("isValid").invoke(surface);
                    } else {
                        // no isValid() below API 9: non-null = ready
                        surfaceReady = true;
                    }
                }
            } catch (Exception ignored) {}

            if (surfaceReady) {
                executeAsyncSetVideoUri(targetUrl);
            } else {
                // Wait for surfaceCreated instead
                videoView.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
                    @Override
                    public void surfaceCreated(android.view.SurfaceHolder holder) {
                        videoView.getHolder().removeCallback(this);
                        // Let VideoView's own surfaceCreated run first
                        videoView.post(new Runnable() {
                            @Override
                            public void run() {
                                executeAsyncSetVideoUri(targetUrl);
                            }
                        });
                    }

                    @Override
                    public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height) {}

                    @Override
                    public void surfaceDestroyed(android.view.SurfaceHolder holder) {}
                });
            }
        } else {
            videoView.setVideoURI(Uri.parse(targetUrl));
            videoView.requestFocus(0);
            setupVideoTimeout();
            videoView.requestLayout();
            videoView.invalidate();
        }
    }

    /** Android 4.1/TouchWiz fix: a playing video can make the tab
     *  contents disappear unless the layout is invalidated. */
    private void triggerLayoutFix(final View targetView) {
        if (targetView == null) return;
        targetView.post(new Runnable() {
            @Override
            public void run() {
                targetView.requestLayout();
                targetView.invalidate();

                // Tablets skip the scroll nudge: it stutters their
                // side-by-side layout.
                if (!isTablet()) {
                    // 1-pixel scroll nudge to force a re-render
                    if (scrollView != null) {
                        scrollView.requestLayout();
                        scrollView.invalidate();
                        scrollView.scrollBy(0, 1);
                        scrollView.scrollBy(0, -1);
                    }
                    if (tabsScrollView != null) {
                        tabsScrollView.requestLayout();
                        tabsScrollView.invalidate();
                        tabsScrollView.scrollBy(0, 1);
                        tabsScrollView.scrollBy(0, -1);
                    }

                    // Re-evaluate the whole window root
                    if (getWindow() != null && getWindow().getDecorView() != null) {
                        getWindow().getDecorView().requestLayout();
                        getWindow().getDecorView().invalidate();
                    }
                }

                checkVisibleItems();
            }
        });
    }

    private void onVideoDataLoaded(final Video fetchedVideo) {
        video = fetchedVideo;

        if (video.channelId != null && video.channelThumbnail != null && video.channelThumbnail.length() > 0)
            ChannelIconResolver.setResolved(video.channelId, video.channelThumbnail);
        findViewById(R.id.loading).setVisibility(View.GONE);
        videoLayout.setVisibility(View.VISIBLE);

        ((TextView) findViewById(R.id.title)).setText(video.title);
        ((TextView) findViewById(R.id.channel_title)).setText(video.channel);
        ((TextView) findViewById(R.id.subscribers)).setText(Utils.formatNumber(context, video.subscribers));
        if (video.likes > 0)
            ((Button) findViewById(R.id.like)).setText(Utils.formatNumber(context, video.likes));
        ((TextView) findViewById(R.id.views)).setText(getString(R.string.views, Utils.formatNumber(context, video.views)) +
                "   " + Utils.formatTimeAgo(context, video.publishedAt));

        resolvedQuality = determineQuality();
        final String quality = resolvedQuality;
        if (!VideoCache.hasValidStream(videoId, quality)) {
            if (config.isConvertVideos() || !"360".equals(quality) || video.videoUrl == null || video.videoUrl.length() == 0) {
                isUsingMetadataUrl = false;
                ensureInstanceForQuality(quality);
                if (videoStream != null) {
                    updatePlaybackViaText(videoStream.getHost());
                }
            } else {
                isUsingMetadataUrl = true;
                updatePlaybackViaText(api.getHost());
            }
        } else {
            videoStream = VideoCache.getVideoStreamInstance();
            isUsingMetadataUrl = (videoStream == null);
            if (videoStream != null) {
                updatePlaybackViaText(videoStream.getHost());
            } else {
                updatePlaybackViaText(api.getHost());
            }
        }

        ImageLoader.loadImage(video.thumbnail, thumbnail, false, null);
        ImageLoader.loadImage(video.channelThumbnail, channelThumbnail, false, null);

        View tabHostView = findViewById(android.R.id.tabhost);
        if (tabHostView != null) {
            tabHostView.setVisibility(View.VISIBLE);
        }

        loadRelatedVideos();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_video, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem qualityItem = menu.findItem(R.id.action_video_quality);
        if (qualityItem != null) {
            qualityItem.setVisible(!config.isConvertVideos());
        }

        MenuItem fullscreenItem = menu.findItem(R.id.action_full_screen);
        if (fullscreenItem != null) {
            boolean isPortrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
            fullscreenItem.setVisible(isPortrait && !isFullscreenMode);
        }
        // The overlay needs the internal VideoView: hidden when an
        // external player does the playback.
        MenuItem subtitlesItem = menu.findItem(R.id.action_subtitles);
        if (subtitlesItem != null) {
            subtitlesItem.setVisible(!config.isUseExternalPlayer());
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();

        if (itemId == R.id.action_video_quality) {
            showQualityDialog();
            return true;
        } else if (itemId == R.id.action_subtitles) {
            showSubtitlesDialog();
            return true;
        } else if (itemId == R.id.action_full_screen) {
            enterFullscreenByConfig();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void enterFullscreenByConfig() {
        isFullscreenMode = true;
        isTabletFullscreen = true;
        if (config.isFullscreenRotateLandscape()) {
            // Lock landscape to block the sensor
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // already landscape
                enterFullscreenMode();
            }
            // Portrait: onConfigurationChanged finishes the job.
        } else {
            enterFullscreenMode();
        }
    }

    /** Quality picker with the 1080p warning and duration checks. */
    private void showQualityDialog() {
        if (video == null) {
            Toast.makeText(context, "Please wait for video to load", Toast.LENGTH_SHORT).show();
            return;
        }

        if (video.length > 3600) {
            Toast.makeText(context, R.string.long_360, Toast.LENGTH_LONG).show();
            return;
        }

        String currentQuality = config.getPreferredQuality();
        if (currentQuality == null) {
            currentQuality = "360";
        }

        final String[] displayQualities = getResources().getStringArray(R.array.qualities);
        final String[] qualities = new String[displayQualities.length];
        for (int i = 0; i < displayQualities.length; i++) {
            qualities[i] = displayQualities[i].replace("p", "");
        }

        int checkedItem = 0;
        for (int i = 0; i < qualities.length; i++) {
            if (qualities[i].equals(currentQuality)) {
                checkedItem = i;
                break;
            }
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.select_quality)
                .setSingleChoiceItems(displayQualities, checkedItem, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        final String selectedQuality = qualities[which];
                        if (selectedQuality.equals(config.getPreferredQuality())) return; // same quality: no reload

                        if ("1080".equals(selectedQuality)) {
                            new AlertDialog.Builder(context)
                                    .setTitle(android.R.string.dialog_alert_title)
                                    .setMessage(R.string.experimental_1080)
                                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, int w) {
                                            applyQuality(selectedQuality);
                                        }
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show();
                        } else applyQuality(selectedQuality);
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Saves the choice, stops playback and re-resolves the stream. */
    private void applyQuality(String newQuality) {
        config.setPreferredQuality(newQuality);
        ConfigManager.getInstance().saveConfig(config);
        config = ConfigManager.getInstance().getConfig();

        resolvedQuality = newQuality;
        videoBufferTimeout = getTimeoutForQuality(newQuality);

        if (videoView != null) {
            rememberPosition();
            resetVideo();
            restoreVideoUI();
        }

        if (video != null) {
            showVideoLoadingUI();
            updateProgressMessage(newQuality);

            if (resolveStreamTask != null) {
                resolveStreamTask.cancel(true);
            }
            if (downloadVideoTask != null) {
                downloadVideoTask.cancel(true);
            }

            if (VideoCache.hasValidStream(videoId, newQuality)) {
                videoUrl = VideoCache.getStreamUrl();
                videoStream = VideoCache.getVideoStreamInstance();
                isUsingMetadataUrl = (videoStream == null);
                proceedPlay(videoUrl);
                return;
            }

            if (config.isConvertVideos() || !"360".equals(newQuality) || video.videoUrl == null || video.videoUrl.length() == 0) {
                isUsingMetadataUrl = false;

                // The instance that served 360p cannot serve 720p/1080p
                // (Piped/Invidious ignore the quality argument): re-pick
                // from the HQ-capable pool.
                ensureInstanceForQuality(newQuality);

                resolveStreamTask = new ResolveStreamTask(videoStream, newQuality);
                resolveStreamTask.execute(videoId, newQuality);
            } else {
                isUsingMetadataUrl = true;
                videoUrl = video.videoUrl;
                VideoCache.putStream(videoId, videoUrl, "360", videoStream);
                proceedPlay(videoUrl);
            }
        }
    }

    private class LoadVideoTask extends AsyncTask<String, Void, Video> {
        private boolean contentUnavailable;

        @Override
        protected Video doInBackground(String... params) {
            try {
                if (isCancelled()) return null;
                return api.getVideo(params[0]);
            } catch (ContentUnavailableException e) {
                contentUnavailable = true;
                e.printStackTrace();
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(final Video fetchedVideo) {
            if (isCancelled()) return;
            if (fetchedVideo == null) {
                findViewById(R.id.loading).setVisibility(View.GONE);
                if (contentUnavailable) {
                    Toast.makeText(context, R.string.content_unavailable, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, R.string.metadata_fail, Toast.LENGTH_SHORT).show();
                }
                return;
            }

            VideoCache.put(videoId, fetchedVideo, api);
            onVideoDataLoaded(fetchedVideo);
        }
    }

    private class ResolveStreamTask extends AsyncTask<String, Void, String> {
        private VideoStream targetInstance;
        private VideoStream[] successInstance = new VideoStream[1];
        private String errorMessage;
        private boolean isFileNotFound;
        private String requestedQuality;
        private String taskQuality;

        ResolveStreamTask(VideoStream targetInstance) {
            this.targetInstance = targetInstance;
        }

        ResolveStreamTask(VideoStream targetInstance, String quality) {
            this.targetInstance = targetInstance;
            this.taskQuality = quality;
        }

        @Override
        protected void onPreExecute() {
            if (targetInstance == null) {
                // Reuse the picked instance only if it serves the quality
                // (a stale 360p-era pick would silently return 360p).
                String quality = taskQuality != null ? taskQuality : config.getPreferredQuality();
                ensureInstanceForQuality(quality);
            } else {
                videoStream = targetInstance;
            }
            if (videoStream != null) {
                updatePlaybackViaText(videoStream.getHost());
            } else {
                updatePlaybackViaText(api.getHost());
            }
        }

        @Override
        protected String doInBackground(String... params) {
            try {
                Utils.waitForConnection(context);
            } catch (IOException e) {
                return null;
            }
            try {
                String id = params[0];
                if (isCancelled()) return null;
                File cachedVideo = getCachedVideoFile(id);
                if (cachedVideo.exists()) return cachedVideo.getAbsolutePath();
                String quality = params.length > 1 ? params[1] : (taskQuality != null ? taskQuality : (resolvedQuality != null ? resolvedQuality : config.getPreferredQuality()));
                if (quality == null) {
                    quality = "360";
                }
                requestedQuality = quality;
                // Keep one dead node from eating the whole buffer budget.
                int timeout = Math.min(getTimeoutForQuality(quality), 40000);
                // A 360p-era instance cannot serve HQ: null it so the
                // Manager iterates the HQ-capable list instead.
                if (targetInstance != null && !config.isConvertVideos()
                        && !"360".equals(quality)
                        && !Manager.getInstance().getHqInstances().contains(targetInstance)) {
                    targetInstance = null;
                }
                if (targetInstance != null) {
                    try {
                        if (config.isConvertVideos() && targetInstance instanceof Conversion) {
                            if (isCancelled()) return null;
                            return ((Conversion) targetInstance).getConvUrl(id, config.getConvertCodec());
                        }
                        if (isCancelled()) return null;
                        return targetInstance.getVideoUrl(id, quality, timeout);
                    } catch (Exception e) {
                        if (isCancelled()) return null;
                        // 404 without conversion = the video is gone: rethrow
                        if (e instanceof java.io.FileNotFoundException && !config.isConvertVideos()) {
                            throw e;
                        }
                        // Drop it from the conversion list on any failure
                        if (config.isConvertVideos() && targetInstance instanceof Conversion) {
                            Manager.getInstance().getConversion().remove((Conversion) targetInstance);
                        }
                        // Fall through to the Manager chain
                        targetInstance = null;
                    }
                }
                if (isCancelled()) return null;
                if (config.isConvertVideos())
                    return Manager.getInstance().getConvUrl(id, quality, config.getConvertCodec(), targetInstance, successInstance);
                return Manager.getInstance().getVideoUrl(id, quality, timeout, targetInstance, successInstance);
            } catch (java.io.FileNotFoundException e) {
                isFileNotFound = true;
                errorMessage = e.getMessage();
                return null;
            } catch (Exception e) {
                errorMessage = e.getMessage();
                return null;
            }
        }

        @Override
        protected void onPostExecute(String resultUrl) {
            if (isCancelled()) return;
            isUsingMetadataUrl = false;

            if (resultUrl != null) {
                if (successInstance[0] != null) {
                    videoStream = successInstance[0];
                }
                VideoCache.putStream(videoId, resultUrl, requestedQuality, videoStream);
                if (resultUrl.startsWith(Environment.getExternalStorageDirectory().getPath())) {
                    updatePlaybackViaText(getString(R.string.cache));
                    proceedPlay(resultUrl);
                } else {
                    if (videoStream != null) updatePlaybackViaText(videoStream.getHost());
                    TextView progressView = (TextView) findViewById(R.id.video_progress);
                    if (config.isConvertVideos()) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (progressView != null) {
                            progressView.setText(R.string.conv_long);
                            progressView.setVisibility(View.VISIBLE);
                        }
                        proceedPlay(resultUrl);
                    } else {
                        proceedPlay(resultUrl);
                    }
                }
            } else if (isFileNotFound) {
                if ("360".equals(requestedQuality)) {
                    resetVideo();
                    restoreVideoUI();
                    if (targetInstance != null) {
                        Manager.getInstance().removeDeadInstance(targetInstance);
                    }
                    Toast.makeText(context, "All instances failed to provide video.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, R.string.no_quality, Toast.LENGTH_SHORT).show();
                    videoBufferTimeout = 60000;
                    if (video != null && video.videoUrl != null && video.videoUrl.length() > 0) {
                        isUsingMetadataUrl = true;
                        updatePlaybackViaText(api.getHost());
                        proceedPlay(video.videoUrl);
                    } else {
                        isUsingMetadataUrl = false;
                        resolveStreamTask = new ResolveStreamTask(null, "360");
                        resolveStreamTask.execute(videoId, "360");
                    }
                }
            } else {
                resetVideo();
                restoreVideoUI();
                updatePlaybackViaText(videoStream != null ? videoStream.getHost() : api.getHost());

                if (errorMessage != null) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show();
                } else if (targetInstance != null) {
                    Manager.getInstance().removeDeadInstance(targetInstance);
                    Toast.makeText(context, "Failed to connect to this instance.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "Failed to fetch video URL", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void proceedPlay(final String targetUrl) {
        boolean isLocal = targetUrl.startsWith(Environment.getExternalStorageDirectory().getPath()) || targetUrl.startsWith("file://");
        boolean forceDownload = config.isConvertVideos() && !isLocal;
        boolean shouldStream = (config.isStreamPlayback() && !forceDownload) || isLocal;

        if (isLocal)
            updatePlaybackViaText(getString(R.string.cache));
        else if (isUsingMetadataUrl)
            updatePlaybackViaText(api.getHost());
        else if (videoStream != null)
            updatePlaybackViaText(videoStream.getHost());

        if (config.isUseExternalPlayer()) {
            if (shouldStream) {
                findViewById(R.id.video_loading).setVisibility(View.GONE);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                intent.setDataAndType(Uri.parse(targetUrl), "video/mp4");
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                downloadVideoTask = new DownloadVideoTask();
                downloadVideoTask.execute(targetUrl);
            }
        } else {
            resetVideo();
            videoPlaying = true;

            if (isActivityStopped) {
                // Activity stopped: onResume handles the playback later.
                videoUrl = shouldStream ? targetUrl : null;
                if (!shouldStream) {
                    downloadVideoTask = new DownloadVideoTask();
                    downloadVideoTask.execute(targetUrl);
                }
                return;
            }

            applyOpenCoreLayoutFix();
            attachVideoListeners();

            if (shouldStream) {
                videoUrl = targetUrl;
                videoView.setVisibility(View.VISIBLE);
                loadVideoUri(targetUrl);
            } else {
                videoUrl = null;
                downloadVideoTask = new DownloadVideoTask();
                downloadVideoTask.execute(targetUrl);
            }
        }
    }

    private class DownloadVideoTask extends AsyncTask<String, Integer, File> {
        private TextView progressView;
        private boolean sdCardNotMounted = false;
        private boolean noSpaceError = false;

        @Override
        protected void onPreExecute() {
            progressView = (TextView) findViewById(R.id.video_progress);
            progressView.setVisibility(View.VISIBLE);
            // Prevent the screen from going to sleep while downloading
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        @Override
        protected File doInBackground(String... params) {
            try {
                if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                    sdCardNotMounted = true;
                    return null;
                }
                File sdCard = Environment.getExternalStorageDirectory();
                android.os.StatFs stat = new android.os.StatFs(sdCard.getPath());
                long availableSpace = (long) stat.getBlockSize() * (long) stat.getAvailableBlocks();
                if (availableSpace < 150L * 1024L * 1024L) { // 150 Megabytes
                    noSpaceError = true;
                    return null;
                }

                String downloadUrl = params[0];
                File videoFile = getCachedVideoFile(videoId);
                if (!videoFile.exists()) {
                    int timeout = config.isConvertVideos() ? HttpClient.CONVERSION_TIMEOUT : videoBufferTimeout;
                    HttpClient.downloadToFile(downloadUrl, videoFile.getAbsolutePath(), timeout, new HttpClient.DownloadProgressListener() {
                        private long lastUpdateTime = 0;
                        private long lastUpdateBytes = 0;

                        @Override
                        public boolean onProgress(final long bytesDownloaded, final long totalBytes) {
                            if (isCancelled()) return false;

                            // "Converting..." until data actually flows
                            if (bytesDownloaded == 0) return true;

                            long currentTime = System.currentTimeMillis();
                            if (lastUpdateTime == 0) {
                                lastUpdateTime = currentTime;
                                lastUpdateBytes = bytesDownloaded;
                                return true;
                            }

                            long timeElapsed = currentTime - lastUpdateTime;
                            if (timeElapsed >= 500) {
                                long bytesSentInInterval = bytesDownloaded - lastUpdateBytes;
                                int speedKB = 0;
                                if (timeElapsed > 0) {
                                    speedKB = (int) ((bytesSentInInterval * 1000) / (timeElapsed * 1024));
                                }
                                int percent = -1;
                                if (totalBytes > 0) {
                                    percent = (int) ((bytesDownloaded * 100) / totalBytes);
                                }

                                // progress, speed and total KB
                                publishProgress(percent, speedKB, (int) (bytesDownloaded / 1024));

                                lastUpdateTime = currentTime;
                                lastUpdateBytes = bytesDownloaded;
                            }
                            return true;
                        }
                    });
                }
                if (isCancelled()) return null;
                return videoFile;
            } catch (Exception e) {
                e.printStackTrace();
                String msg = e.getMessage();
                if (e instanceof IOException && msg != null &&
                        (msg.toLowerCase().contains("no space left") || msg.contains("ENOSPC"))) {
                    noSpaceError = true;
                }
                return null;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (values.length >= 2) {
                int percent = values[0];
                int speedKB = values[1];
                if (percent >= 0) {
                    progressView.setText(getString(R.string.download_progress_percent, percent, speedKB));
                } else {
                    if (values.length >= 3) {
                        double downloadedMB = values[2] / 1024.0;
                        progressView.setText(String.format(getString(R.string.download_progress_mb), downloadedMB, speedKB));
                    } else {
                        progressView.setText(getString(R.string.download_speed, speedKB));
                    }
                }
            } else if (values.length == 1) {
                progressView.setText(getString(R.string.percent, values[0]));
            }
        }

        @Override
        protected void onCancelled() {
            // Download aborted
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (progressView != null) {
                progressView.setVisibility(View.GONE);
            }
        }

        @Override
        protected void onPostExecute(File videoFile) {
            if (isCancelled()) return;
            if (progressView != null) progressView.setVisibility(View.GONE);

            if (sdCardNotMounted) {
                resetVideo(); restoreVideoUI();
                Toast.makeText(context, R.string.sd_card, Toast.LENGTH_LONG).show();
                return;
            } else if (noSpaceError) {
                resetVideo(); restoreVideoUI();
                Toast.makeText(context, R.string.sd_card_space, Toast.LENGTH_LONG).show();
                return;
            }

            if (videoFile != null) {
                if (config.isUseExternalPlayer()) {
                    findViewById(R.id.video_loading).setVisibility(View.GONE);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.fromFile(videoFile), "video/mp4");
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    resetVideo();
                    videoUrl = Uri.fromFile(videoFile).toString();
                    videoPlaying = true;
                    if (isActivityStopped) return;
                    applyOpenCoreLayoutFix();
                    attachVideoListeners();

                    videoView.setVisibility(View.VISIBLE);
                    loadVideoUri(videoUrl);
                }
            } else {
                handleStreamFailure(false, "Download failed after multiple attempts.");
            }
        }
    }

    // ------------------------------------------------------------------
    // Subtitles UI (track list once per video, VTT once per track)
    // ------------------------------------------------------------------

    /** Opens the picker, fetching the track list on first open. */
    private void showSubtitlesDialog() {
        if (video == null) {
            Toast.makeText(context, R.string.metadata_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        if (subtitleTracks != null) {
            buildSubtitlesDialog();
            return;
        }
        if (subtitlesTaskRunning) return;
        subtitlesTaskRunning = true;
        Toast.makeText(context, R.string.subtitles_loading, Toast.LENGTH_SHORT).show();
        new LoadSubtitlesTask().execute(videoId);
    }

    /** Builds the "Off" + track picker with the active track preselected. */
    private void buildSubtitlesDialog() {
        if (subtitleTracks == null || isFinishing()) return;
        final List<SubtitleTrack> tracks = subtitleTracks;
        final String[] labels = new String[tracks.size() + 1];
        labels[0] = getString(R.string.subtitles_off);
        int checked = 0;
        for (int i = 0; i < tracks.size(); i++) {
            String label = tracks.get(i).label;
            if (label == null || label.length() == 0) label = tracks.get(i).code;
            labels[i + 1] = label;
            if (activeSubtitleTrack != null && activeSubtitleTrack.url.equals(tracks.get(i).url)) {
                checked = i + 1;
            }
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.subtitles)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            disableSubtitles();
                        } else {
                            selectSubtitles(tracks.get(which - 1));
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Turns the subtitle overlay off and stops its ticker. */
    private void disableSubtitles() {
        activeSubtitleTrack = null;
        subtitles = null;
        lastSubtitleText = null;
        stopSubtitleTicker();
        if (subtitleView != null) subtitleView.setVisibility(View.GONE);
    }

    /** Activates the track: the VTT is downloaded once (racing several
     *  candidate URLs — the advertised one is often dead), parsed into
     *  cue arrays and shown by the ticker. */
    private void selectSubtitles(final SubtitleTrack track) {
        activeSubtitleTrack = track;
        // Drop any previous cues while the new file downloads.
        subtitles = null;
        lastSubtitleText = null;
        if (subtitleView != null) subtitleView.setVisibility(View.GONE);
        String label = track.label != null && track.label.length() > 0 ? track.label : track.code;
        Toast.makeText(context, label, Toast.LENGTH_SHORT).show();
        new LoadVttTask().execute(buildVttCandidates(track));
    }

    /** Invidious caption fallbacks tried per selected track. */
    private static final int MAX_CAPTION_FALLBACKS = 12;

    /** Timeout of the solo fast-path download (remembered winner). */
    private static final int VTT_FAST_TIMEOUT_MS = 3500;
    /** Per-candidate timeout inside the parallel race. */
    private static final int VTT_RACE_TIMEOUT_MS = 7000;
    /** Global budget of the race wave. */
    private static final long VTT_RACE_TOTAL_MS = 10000;

    /** Candidate URLs for one track: remembered winner, the advertised
     *  URL (+ http downgrade), then the /api/v1/captions endpoints of the
     *  configured Invidious instances (capped). All raced in parallel. */
    private String[] buildVttCandidates(SubtitleTrack track) {
        List<String> urls = new ArrayList<String>();
        // 0. Remembered winner first (solo fast path in LoadVttTask).
        String winnerBase = Manager.rememberedInvidiousCaptionBase();
        if (videoId != null && videoId.length() > 0 && winnerBase != null && winnerBase.length() > 0) {
            String b = winnerBase;
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            if (Manager.rememberedInvidiousCaptionLabel() && track.label != null && track.label.length() > 0) {
                addUnique(urls, b + "/api/v1/captions/" + videoId + "?label=" + encode(track.label));
            }
            if (track.code != null && track.code.length() > 0) {
                addUnique(urls, b + "/api/v1/captions/" + videoId + "?lang=" + encode(track.code));
            }
        }
        String url = track.url == null ? "" : track.url;
        if (url.startsWith("http") && url.indexOf("youtube.com/api/timedtext") == -1) {
            urls.add(ensureVttFormat(url));
            // Same proxy over plain http (legacy-TLS escape hatch)
            String down = Manager.httpDowngrade(ensureVttFormat(url));
            if (down != null) addUnique(urls, down);
        }
        if (videoId != null && videoId.length() > 0) {
            List<String> invList = config.getInvidiousInstances();
            int used = 0;
            for (int i = 0; i < invList.size() && used < MAX_CAPTION_FALLBACKS; i++) {
                String base = invList.get(i);
                if (base == null) continue;
                String b = base.trim();
                while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
                if (b.length() == 0) continue;
                used++;
                if (track.label != null && track.label.length() > 0) {
                    addUnique(urls, b + "/api/v1/captions/" + videoId + "?label=" + encode(track.label));
                }
                if (track.code != null && track.code.length() > 0) {
                    addUnique(urls, b + "/api/v1/captions/" + videoId + "?lang=" + encode(track.code));
                }
            }
        }
        return urls.toArray(new String[urls.size()]);
    }

    /** Extracts the "http://host[:port]" prefix of an Invidious captions
     *  URL, or null when the URL is not one. */
    private static String invidiousCaptionsBase(String url) {
        if (url == null) return null;
        int idx = url.indexOf("/api/v1/captions/");
        if (idx == -1) return null;
        String base = url.substring(0, idx);
        if (base.startsWith("http://") || base.startsWith("https://")) return base;
        return null;
    }

    /** Append-only list add that keeps the candidate chain duplicate-free. */
    private static void addUnique(List<String> urls, String url) {
        for (int i = 0; i < urls.size(); i++) {
            if (urls.get(i).equals(url)) return;
        }
        urls.add(url);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s.replace(' ', '+'); // UTF-8 always exists
        }
    }

    /** Asks for WebVTT (fmt=vtt), the only format the renderer parses. */
    private static String ensureVttFormat(String url) {
        if (url.indexOf("timedtext") == -1) return url;
        if (url.contains("fmt=ttml")) return url.replace("fmt=ttml", "fmt=vtt");
        if (url.indexOf("fmt=") == -1) {
            return url + (url.indexOf('?') != -1 ? "&" : "?") + "fmt=vtt";
        }
        return url;
    }

    /** Lazily creates the caption overlay inside the video frame. */
    private void ensureSubtitleView() {
        if (subtitleView != null) return;
        subtitleView = new TextView(context);
        subtitleView.setTextColor(Color.WHITE);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        subtitleView.setGravity(android.view.Gravity.CENTER);
        subtitleView.setShadowLayer(2f, 1f, 1f, Color.BLACK);
        subtitleView.setBackgroundColor(0x66000000);
        subtitleView.setPadding(8, 4, 8, 4);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM);
        subtitleView.setLayoutParams(lp);
        videoFrame.addView(subtitleView);
    }

    private void startSubtitleTicker() {
        subtitleHandler.removeCallbacks(subtitleTick);
        subtitleHandler.postDelayed(subtitleTick, 300);
    }

    private void stopSubtitleTicker() {
        subtitleHandler.removeCallbacks(subtitleTick);
    }

    /** Fetches the caption tracks (once per video). Piped first: its
     *  caption URLs actually resolve, while Invidious bodies are
     *  bot-blocked (its list still helps as a picker fallback). */
    private class LoadSubtitlesTask extends AsyncTask<String, Void, List<SubtitleTrack>> {
        @Override
        protected List<SubtitleTrack> doInBackground(String... params) {
            try {
                if (isCancelled()) return null;
                // 1. Piped: healthy list, definitive even when empty.
                List<SubtitleTrack> piped = Manager.getInstance().getPipedSubtitles(params[0]);
                if (piped != null) return piped;
                // 2. General pool (Invidious first in its list): the picker
                //    still gets tracks, and LoadVttTask re-tries Piped for
                //    the actual caption body.
                return api.getSubtitles(params[0]);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<SubtitleTrack> result) {
            subtitlesTaskRunning = false;
            if (isFinishing()) return;
            if (result == null) {
                Toast.makeText(context, R.string.subtitles_error, Toast.LENGTH_LONG).show();
                return;
            }
            subtitleTracks = result;
            if (subtitleTracks.isEmpty()) {
                Toast.makeText(context, R.string.no_subtitles, Toast.LENGTH_LONG).show();
                return;
            }
            buildSubtitlesDialog();
        }
    }

    /** Downloads the VTT: remembered winner solo (fast path), then one
     *  parallel race over all candidates, then live Piped. The winner is
     *  remembered for the next selection. */
    private class LoadVttTask extends AsyncTask<String, Void, VttSubtitles> {
        @Override
        protected VttSubtitles doInBackground(String... params) {
            if (isCancelled()) return null;
            // 1. Solo fast path: remembered winner, short budget.
            String rememberedBase = Manager.rememberedInvidiousCaptionBase();
            if (rememberedBase != null && params.length > 0) {
                String first = params[0];
                String base = invidiousCaptionsBase(first);
                if (base != null && base.equals(rememberedBase)) {
                    VttSubtitles fast = tryFetchVtt(first, VTT_FAST_TIMEOUT_MS);
                    if (fast != null && fast.size() > 0) {
                        recordVttWinner(first);
                        return fast;
                    }
                }
            }
            if (isCancelled()) return null;
            // 2. One parallel wave over every candidate left.
            String[] winnerUrl = new String[1];
            VttSubtitles parsed = raceVttCandidates(params, VTT_RACE_TOTAL_MS, VTT_RACE_TIMEOUT_MS,
                    winnerUrl, this);
            if (parsed != null && parsed.size() > 0) {
                if (winnerUrl[0] != null) recordVttWinner(winnerUrl[0]);
                return parsed;
            }
            if (isCancelled()) return null;
            return fetchPipedVtt();
        }

        @Override
        protected void onPostExecute(VttSubtitles parsed) {
            if (isFinishing()) return;
            if (parsed == null || parsed.size() == 0) {
                activeSubtitleTrack = null;
                Toast.makeText(context, R.string.subtitles_error, Toast.LENGTH_LONG).show();
                return;
            }
            subtitles = parsed;
            lastSubtitleText = null;
            startSubtitleTicker();
        }
    }

    /** Stores a winning Invidious captions endpoint for the next load. */
    private static void recordVttWinner(String url) {
        String base = invidiousCaptionsBase(url);
        if (base == null) return; // advertised Piped URLs are not reusable
        Manager.rememberInvidiousCaptionWinner(base, url.indexOf("?label=") != -1);
    }

    /** Parallel download wave: every candidate starts at once, the first
     *  body that parses wins (its URL stored into winnerUrl). Null when
     *  all fail. Java 1.5: plain threads + wait/notify. */
    private VttSubtitles raceVttCandidates(String[] urls, final long totalBudgetMs, final long requestTimeoutMs,
            final String[] winnerUrl, AsyncTask<?, ?, ?> task) {
        if (urls == null || urls.length == 0) return null;
        final VttSubtitles[] winner = new VttSubtitles[1];
        final String[] winnerCandidate = winnerUrl != null ? winnerUrl : new String[1];
        final java.util.concurrent.atomic.AtomicInteger finished =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final Object lock = new Object();
        final int total = urls.length;
        for (int i = 0; i < total; i++) {
            final String url = urls[i];
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        VttSubtitles v = tryFetchVtt(url, (int) requestTimeoutMs);
                        if (v != null && v.size() > 0) {
                            synchronized (lock) {
                                if (winner[0] == null) {
                                    winner[0] = v;
                                    winnerCandidate[0] = url;
                                }
                            }
                        }
                    } finally {
                        finished.incrementAndGet();
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                }
            }, "vtt-race");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }
        long deadline = System.currentTimeMillis() + totalBudgetMs;
        synchronized (lock) {
            while (winner[0] == null && finished.get() < total
                    && System.currentTimeMillis() < deadline && !task.isCancelled()) {
                try {
                    lock.wait(250);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return winner[0];
    }

    /** Last resort: the live Piped list of this video plus the body of the
     *  track matching the selected language. */
    private VttSubtitles fetchPipedVtt() {
        if (videoId == null || videoId.length() == 0 || activeSubtitleTrack == null) return null;
        List<SubtitleTrack> pipedTracks;
        try {
            pipedTracks = Manager.getInstance().getPipedSubtitles(videoId);
        } catch (Exception e) {
            return null;
        }
        if (pipedTracks == null || pipedTracks.isEmpty()) return null;
        String code = activeSubtitleTrack.code;
        boolean hasCode = code != null && code.length() > 0;
        // Pass 1: every route of the selected language (Piped lists auto and
        // manual captions of one language as separate same-code tracks).
        if (hasCode) {
            for (int i = 0; i < pipedTracks.size(); i++) {
                SubtitleTrack t = pipedTracks.get(i);
                if (!code.equals(t.code)) continue;
                VttSubtitles parsed = tryFetchVttWithDowngrade(t.url);
                if (parsed != null) return parsed;
            }
            // Pass 2: same primary language ("en-GB" -> accept "en").
            int dash = code.indexOf('-');
            if (dash > 0) {
                String primary = code.substring(0, dash);
                for (int i = 0; i < pipedTracks.size(); i++) {
                    SubtitleTrack t = pipedTracks.get(i);
                    if (t.code == null || !t.code.startsWith(primary)) continue;
                    VttSubtitles parsed = tryFetchVttWithDowngrade(t.url);
                    if (parsed != null) return parsed;
                }
            }
        } else {
            // The selected track carried no language code: take the first
            // body that parses.
            for (int i = 0; i < pipedTracks.size(); i++) {
                VttSubtitles parsed = tryFetchVttWithDowngrade(pipedTracks.get(i).url);
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    /** One candidate VTT download; null when the body does not yield cues. */
    private VttSubtitles tryFetchVtt(String url, int timeoutMs) {
        if (url == null || url.length() == 0) return null;
        try {
            HttpRequest req = new HttpRequest(url);
            String vtt = HttpClient.executeToString(req, timeoutMs);
            VttSubtitles parsed = VttSubtitles.parse(vtt);
            if (parsed != null && parsed.size() > 0) return parsed;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** tryFetchVtt plus the plain-http escape hatch of the same URL. */
    private VttSubtitles tryFetchVttWithDowngrade(String url) {
        if (url == null || url.length() == 0) return null;
        VttSubtitles parsed = tryFetchVtt(url, VTT_RACE_TIMEOUT_MS);
        if (parsed != null) return parsed;
        String down = Manager.httpDowngrade(url);
        if (down != null) parsed = tryFetchVtt(down, VTT_RACE_TIMEOUT_MS);
        return parsed;
    }

    private class LoadCommentsTask extends AsyncTask<String, Void, List<Comment>> {
        private boolean commentsUnavailable;

        @Override
        protected List<Comment> doInBackground(String... params) {
            try {
                if (isCancelled()) return null;
                return api.getComments(params[0]);
            } catch (ContentUnavailableException e) {
                // No/disabled comments (e.g. made for kids): not an error.
                commentsUnavailable = true;
                return null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<Comment> result) {
            if (isCancelled()) return;
            TextView noCommentsView = (TextView) findViewById(R.id.no_comments);

            if (result != null) {
                comments.clear();
                comments.addAll(result);
                commentsAdapter.notifyDataSetChanged();
                commentsLoaded = true;

                if (noCommentsView != null) {
                    noCommentsView.setText(R.string.no_comments);
                    noCommentsView.setClickable(false);
                    if (comments.isEmpty()) {
                        noCommentsView.setVisibility(View.VISIBLE);
                    } else {
                        noCommentsView.setVisibility(View.GONE);
                    }
                }
            } else if (commentsUnavailable) {
                commentsLoaded = true;
                if (noCommentsView != null) {
                    noCommentsView.setText(R.string.no_comments);
                    noCommentsView.setClickable(false);
                    noCommentsView.setVisibility(View.VISIBLE);
                }
            } else {
                // All instances failed: offer a tappable retry.
                if (noCommentsView != null) {
                    noCommentsView.setText(R.string.comments_error);
                    noCommentsView.setVisibility(View.VISIBLE);
                    noCommentsView.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            v.setVisibility(View.GONE);
                            commentsLoading.setVisibility(View.VISIBLE);
                            loadCommentsTask = new LoadCommentsTask();
                            loadCommentsTask.execute(videoId);
                        }
                    });
                }
            }
            commentsLoading.setVisibility(View.GONE);

            triggerLayoutFix(commentsList);
        }
    }

    // ------------------------------------------------------------------
    // Comment replies (NewPipe-style inline expand/collapse)
    // ------------------------------------------------------------------

    /** Expands or collapses the replies of one comment. The first
     *  expansion fetches the replies page through the comment's own
     *  continuation URL; legacy Invidious rows embed their replies. */
    private void toggleCommentReplies(Comment parent) {
        if (parent == null || commentsAdapter == null || isFinishing()) return;
        if (parent.repliesShown) {
            int idx = comments.indexOf(parent);
            if (idx >= 0) {
                int i = idx + 1;
                while (i < comments.size() && comments.get(i).isReply) {
                    comments.remove(i);
                }
            }
            parent.repliesShown = false;
            commentsAdapter.notifyDataSetChanged();
            return;
        }
        // Legacy Invidious: replies came inline with the comment.
        if (parent.preloadedReplies != null && !parent.preloadedReplies.isEmpty()) {
            insertCommentReplies(parent, parent.preloadedReplies);
            return;
        }
        if (parent.repliesUrl == null || parent.repliesUrl.length() == 0 || parent.repliesLoading) {
            return; // advertised count without a usable continuation
        }
        parent.repliesLoading = true;
        commentsAdapter.notifyDataSetChanged(); // "Loading replies..."
        new LoadRepliesTask().execute(parent);
    }

    /** Inserts reply rows under their parent and refreshes the list. */
    private void insertCommentReplies(Comment parent, List<Comment> replies) {
        parent.repliesLoading = false;
        if (replies == null || replies.isEmpty()) {
            // Definitive empty answer: drop the toggle. Null (network
            // failure) keeps the count so tapping retries.
            if (replies != null) parent.replyCount = 0;
            commentsAdapter.notifyDataSetChanged();
            return;
        }
        int idx = comments.indexOf(parent);
        if (idx < 0) return; // parent no longer displayed
        // Replace any previously inserted replies of this parent.
        int i = idx + 1;
        while (i < comments.size() && comments.get(i).isReply) {
            comments.remove(i);
        }
        for (int r = 0; r < replies.size(); r++) {
            Comment reply = replies.get(r);
            reply.isReply = true;
            comments.add(idx + 1 + r, reply);
        }
        parent.repliesShown = true;
        commentsAdapter.notifyDataSetChanged();
        triggerLayoutFix(commentsList);
    }

    /** Fetches and parses one replies page in the background. */
    private class LoadRepliesTask extends AsyncTask<Comment, Void, List<Comment>> {
        private Comment parent;

        @Override
        protected List<Comment> doInBackground(Comment... params) {
            parent = params[0];
            try {
                HttpRequest req = new HttpRequest(parent.repliesUrl);
                String body = HttpClient.executeToString(req, 15000);
                return Manager.getInstance().parseCommentReplies(parent.backend, body);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<Comment> result) {
            if (isCancelled() || isFinishing()) return;
            if (parent == null) return;
            insertCommentReplies(parent, result);
        }
    }

    private class LoadRelatedTask extends AsyncTask<String, Void, List<VideoInfo>> {
        @Override
        protected List<VideoInfo> doInBackground(String... params) {
            try {
                if (isCancelled()) return null;
                return api.getRelated(params[0]);
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<VideoInfo> result) {
            if (isCancelled()) return;
            TextView noRelatedView = (TextView) findViewById(R.id.no_related);
            if (result != null) {
                relatedVideos.clear();
                relatedVideos.addAll(result);
                relatedAdapter.notifyDataSetChanged();
                relatedLoaded = true;
                if (noRelatedView != null) {
                    if (relatedVideos.isEmpty()) {
                        noRelatedView.setVisibility(View.VISIBLE);
                    } else {
                        noRelatedView.setVisibility(View.GONE);
                    }
                }
            } else {
                if (noRelatedView != null) noRelatedView.setVisibility(View.GONE);
            }
            relatedLoading.setVisibility(View.GONE);
            triggerLayoutFix(relatedList);
        }
    }
}