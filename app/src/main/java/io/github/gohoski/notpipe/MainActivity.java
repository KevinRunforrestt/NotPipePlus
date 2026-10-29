package io.github.gohoski.notpipe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.ui.AutoCompleteAdapter;
import io.github.gohoski.notpipe.ui.PullToRefreshLayout;
import io.github.gohoski.notpipe.ui.VideoAdapter;
import io.github.gohoski.notpipe.util.ChannelIconResolver;
import io.github.gohoski.notpipe.util.ImageLoader;
import io.github.gohoski.notpipe.util.InstancesUpdater;

public class MainActivity extends Activity implements InstancesUpdater.OnInstancesUpdatedListener,
        FeedManager.FeedObserver {
    private static final String STATE_SEARCH_QUERY = "search_query";
    private static final String STATE_IS_SEARCH_MODE = "is_search_mode";

    /** A feed older than this is refreshed automatically in the
     *  "on app open" mode. */
    private static final long FEED_STALE_MS = 15L * 60L * 1000L;

    /** Feed refresh reasons. MANUAL and REBUILD check every channel; AUTO
     *  only outdated ones. */
    private static final int FEED_REFRESH_AUTO = 0;
    private static final int FEED_REFRESH_MANUAL = 1;
    private static final int FEED_REFRESH_REBUILD = 2;

    private VideoAdapter adapter;
    private List<VideoInfo> videos;
    private AutoCompleteTextView searchQuery;
    private AbsListView listView;
    private Context context;
    private Metadata metadata;
    private AutoCompleteAdapter autoCompleteAdapter;
    private Config config = ConfigManager.getInstance().getConfig();
    private boolean isSearchMode = false;
    private boolean isDestroyedFlag = false;

    // Local subscriptions feed ("Subscriptions" tab)
    private TabHost tabHost;
    private AbsListView subsListView;
    private VideoAdapter subsAdapter;
    private List<VideoInfo> subsFeed;
    private boolean feedLoaded = false;
    private boolean feedLoading = false;
    private boolean feedError = false;
    private int subsCount = -1;
    /** Centered loading overlay of the Subscriptions tab (may be absent in
     *  alternative layouts). */
    private View subsOverlay;
    /** True when the Popular feed is hidden by settings (subs-only mode). */
    private boolean hidePopular = false;
    /** Hops FeedManager observer callbacks to the UI thread. */
    private final Handler feedUiHandler = new Handler();
    /** Seq of the last feed result already applied (guards a double apply
     *  across the rotation gap). */
    private long lastAppliedFeedSeq = 0;
    /** Wall-clock time of the last successful feed load; 0 = unknown. */
    private long feedLoadedAt = 0;
    /** Refresh progress bar + captions (NewPipe-style). */
    private ProgressBar subsProgress;
    private TextView subsProgressText;
    private TextView subsLastUpdate;
    private PullToRefreshLayout subsPull;
    /** True after a rotation: the retained feed is on screen, the first
     *  onResume must not auto-refresh again. */
    private boolean skipFirstAutoRefresh = false;
    /** False until the first onResume; on-open refreshes happen there. */
    private boolean firstResume = true;

    /** In-app timer for the interval mode (armed only while resumed: a
     *  background service would not survive reliably on Android 2.3). */
    private final Handler feedTimer = new Handler();
    private final Runnable feedTimerTask = new Runnable() {
        public void run() {
            if (isDestroyedFlag) return;
            if (config.getFeedRefreshMode() == Config.FEED_REFRESH_INTERVAL && !feedLoading) {
                long interval = config.getFeedRefreshIntervalMinutes() * 60L * 1000L;
                if (feedLoadedAt <= 0 || System.currentTimeMillis() - feedLoadedAt >= interval) {
                    startFeedLoad(FEED_REFRESH_AUTO);
                }
            }
            scheduleFeedTimer();
        }
    };

    private static class RetainedState {
        List<VideoInfo> videos;
        boolean isSearchMode;
        String query;
        List<VideoInfo> subsFeed;
        boolean feedLoaded;
        long feedLoadedAt;
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        RetainedState state = new RetainedState();
        state.videos = this.videos;
        state.isSearchMode = this.isSearchMode;
        if (searchQuery != null) {
            state.query = searchQuery.getText().toString();
        }
        state.subsFeed = this.subsFeed;
        state.feedLoaded = this.feedLoaded;
        state.feedLoadedAt = this.feedLoadedAt;
        return state;
    }

    private void setAdapterForView(AbsListView view, VideoAdapter adapter) {
        if (view instanceof ListView) {
            ((ListView) view).setAdapter(adapter);
        } else if (view instanceof GridView) {
            ((GridView) view).setAdapter(adapter);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = (AbsListView) findViewById(R.id.videos);
        searchQuery = (AutoCompleteTextView) findViewById(R.id.search_query);
        final ImageButton searchBtn = (ImageButton) findViewById(R.id.search_btn);
        final ProgressBar loading = (ProgressBar) findViewById(R.id.loading);
        final LinearLayout noPopular = (LinearLayout) findViewById(R.id.no_popular);
        context = this;

        tabHost = (TabHost) findViewById(R.id.main_tabhost);
        hidePopular = config.isHidePopularFeed();
        if (tabHost != null) {
            tabHost.setup();
            if (hidePopular) {
                // "Hide Popular": no tab registered, the subs feed is the
                // home screen; search results borrow the hidden list.
                findViewById(R.id.popular_tab).setVisibility(View.GONE);
            } else {
                tabHost.addTab(tabHost.newTabSpec("popular")
                        .setIndicator(getString(R.string.popular_tab))
                        .setContent(R.id.popular_tab));
            }
            tabHost.addTab(tabHost.newTabSpec("subs")
                    .setIndicator(getString(R.string.subs_tab))
                    .setContent(R.id.subs_tab));
            tabHost.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
                public void onTabChanged(String tabId) {
                    if ("subs".equals(tabId)) {
                        updateSubsViews();
                        maybeAutoRefresh(false);
                    }
                }
            });
            if (hidePopular) {
                // One tab needs no tab bar.
                tabHost.getTabWidget().setVisibility(View.GONE);
            }
            if (NotPipe.SDK < 11) {
                // The default TabWidget indicator is too tall on old Androids.
                int h = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 33f,
                        getResources().getDisplayMetrics());
                android.widget.TabWidget tw = tabHost.getTabWidget();
                for (int i = 0; i < tw.getChildCount(); i++) {
                    View child = tw.getChildAt(i);
                    if (child != null && child.getLayoutParams() != null) {
                        child.getLayoutParams().height = h;
                    }
                }
            }
        }

        subsListView = (AbsListView) findViewById(R.id.subs_list);
        subsProgress = (ProgressBar) findViewById(R.id.subs_progress);
        subsProgressText = (TextView) findViewById(R.id.subs_progress_text);
        subsLastUpdate = (TextView) findViewById(R.id.subs_last_update);
        subsOverlay = findViewById(R.id.subs_loading_overlay);
        subsPull = (PullToRefreshLayout) findViewById(R.id.subs_pull);
        if (subsPull != null) {
            if (subsListView != null) subsPull.setTarget(subsListView);
            // Manual refresh (any mode): the pull gesture checks every channel.
            subsPull.setOnRefreshListener(new PullToRefreshLayout.OnRefreshListener() {
                public void onRefresh() {
                    if (!feedLoading && Subscriptions.count() > 0) {
                        startFeedLoad(FEED_REFRESH_MANUAL);
                    } else {
                        subsPull.onRefreshComplete();
                    }
                }
            });
        }
        TextView emptySubsView = (TextView) findViewById(R.id.empty_subs);
        if (emptySubsView != null) {
            // "Tap to retry" when the feed failed.
            emptySubsView.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!feedLoading && Subscriptions.count() > 0) {
                        startFeedLoad(FEED_REFRESH_MANUAL);
                    }
                }
            });
        }
        subsListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // No clearCache/gc on the click path: it froze weak devices.
                Intent intent = new Intent(MainActivity.this, VideoActivity.class);
                intent.putExtra("ID", ((VideoInfo) parent.getItemAtPosition(position)).id);
                startActivity(intent);
            }
        });

        // (Scroll-idle notifyDataSetChanged listeners were removed: they
        // only burned CPU on weak devices.)
        View manageBtn = findViewById(R.id.manage_subs_btn);
        if (manageBtn != null) {
            manageBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, SubscriptionsActivity.class));
                }
            });
        }

        try {
            metadata = Manager.getInstance().getMetadata();
        } catch(IllegalStateException ignored) {}

        RetainedState retained = (RetainedState) getLastNonConfigurationInstance();
        if (retained != null) {
            videos = retained.videos;
            isSearchMode = retained.isSearchMode;
            subsFeed = retained.subsFeed;
            feedLoaded = retained.feedLoaded;
            feedLoadedAt = retained.feedLoadedAt;
        } else if (savedInstanceState != null) {
            isSearchMode = savedInstanceState.getBoolean(STATE_IS_SEARCH_MODE, false);
        }

        // Feed bootstrap: retained state (rotation) wins, then the on-disk
        // cache paints the tab instantly; the refresh is decided in
        // maybeAutoRefresh() on the first onResume.
        if (subsCount == -1) {
            subsCount = Subscriptions.count();
        }
        skipFirstAutoRefresh = retained != null && subsFeed != null && !subsFeed.isEmpty();
        if (subsFeed == null && subsCount > 0) {
            FeedLogic.CachedFeed cached = FeedManager.loadCachedFeed();
            if (cached != null && cached.videos != null && !cached.videos.isEmpty()) {
                subsFeed = cached.videos;
                feedLoaded = true;
                feedLoadedAt = cached.loadedAt;
            }
        }
        if (subsFeed != null && !subsFeed.isEmpty()) {
            applyFeed(subsFeed);
        }
        // A refresh started before a rotation keeps running in the
        // process-wide coordinator: re-attach to its live progress.
        feedLoading = FeedManager.isUpdating();
        if (feedLoading) {
            updateFeedProgress(FeedManager.getProgressCurrent(), FeedManager.getProgressTotal());
        }
        updateSubsViews();

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // No clearCache/gc on the click path (see subsListView above).
                Intent intent = new Intent(MainActivity.this, VideoActivity.class);
                intent.putExtra("ID", ((VideoInfo) parent.getItemAtPosition(position)).id);
                startActivity(intent);
            }
        });

        autoCompleteAdapter = new AutoCompleteAdapter(this);
        autoCompleteAdapter.setOnSuggestionsLoadedListener(new AutoCompleteAdapter.OnSuggestionsLoadedListener() {
            @Override
            public void onSuggestionsLoaded() {
                if (!isDestroyedFlag && searchQuery != null) {
                    searchQuery.showDropDown();
                }
            }
        });
        searchQuery.setAdapter(autoCompleteAdapter);
        searchQuery.setThreshold(3);

        if (retained != null && retained.query != null) {
            searchQuery.setText(retained.query);
        } else if (savedInstanceState != null) {
            String query = savedInstanceState.getString(STATE_SEARCH_QUERY);
            if (query != null) searchQuery.setText(query);
        }
        searchQuery.dismissDropDown();

        searchQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchQuery.hasFocus()) {
                    autoCompleteAdapter.setSearchActive(false);
                    autoCompleteAdapter.requestSuggestions(s.toString());
                }
            }
        });
        searchQuery.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String suggestion = (String) parent.getItemAtPosition(position);
                autoCompleteAdapter.setSearchActive(true);
                searchQuery.setText(suggestion);
                hideKeyboard();
                searchBtn.performClick();
            }
        });
        searchQuery.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    searchBtn.performClick();
                    return true;
                }
                return false;
            }
        });

        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String query = searchQuery.getText().toString().trim();
                if (query.length() != 0) {
                    String videoId = extractYouTubeId(query);
                    if (videoId != null) {
                        hideKeyboard();
                        Intent intent = new Intent(MainActivity.this, VideoActivity.class);
                        intent.putExtra("ID", videoId);
                        startActivity(intent);
                        return;
                    }
                    searchQuery.dismissDropDown();
                    autoCompleteAdapter.setSearchActive(true);
                    isSearchMode = true;
                    // Search results live in the Popular tab's list: switch
                    // to it so the results are visible (shown as an overlay
                    // in the "hide Popular" mode).
                    if (hidePopular) {
                        setSearchOverlayVisible(true);
                    } else {
                        resetToPopularTab();
                    }
                    noPopular.setVisibility(View.GONE);
                    loading.setVisibility(View.VISIBLE);
                    View emptyView = findViewById(R.id.empty_videos);
                    if (emptyView != null) emptyView.setVisibility(View.GONE);
                    hideKeyboard();
                    new SearchTask().execute(query);
                }
            }
        });

        if (videos == null && hidePopular && isSearchMode
                && searchQuery.getText().toString().trim().length() > 0) {
            // Restored search in subs-only mode: results overlay visible.
            setSearchOverlayVisible(true);
        }

        if (videos == null) {
            boolean isUpdatingInstances = false;
            if (config.isUpdateInstancesFromUrl()) {
                Calendar now = Calendar.getInstance();
                Calendar lastUpdate = Calendar.getInstance();
                now.setTimeInMillis(System.currentTimeMillis());
                lastUpdate.setTimeInMillis(config.getLastUpdate());
                now.set(Calendar.HOUR_OF_DAY, 0);
                now.set(Calendar.MINUTE, 0);
                now.set(Calendar.SECOND, 0);
                now.set(Calendar.MILLISECOND, 0);
                lastUpdate.set(Calendar.HOUR_OF_DAY, 0);
                lastUpdate.set(Calendar.MINUTE, 0);
                lastUpdate.set(Calendar.SECOND, 0);
                lastUpdate.set(Calendar.MILLISECOND, 0);
                if (Math.round((double) ((now.getTimeInMillis() - lastUpdate.getTimeInMillis()) / (24L * 60 * 60 * 1000))) >= config.getUpdateFrequency()) {
                    isUpdatingInstances = true;
                    new InstancesUpdater(this, this).updateInstances();
                }
            }

            if (!isUpdatingInstances) {
                if (isSearchMode && searchQuery.getText().toString().trim().length() > 0) {
                    if (hidePopular) setSearchOverlayVisible(true);
                    loading.setVisibility(View.VISIBLE);
                    new SearchTask().execute(searchQuery.getText().toString().trim());
                } else if (hidePopular) {
                    // Popular hidden: the subs feed is the home screen.
                    isSearchMode = false;
                } else {
                    isSearchMode = false;
                    new PopularTask().execute();
                }
            }
        } else {
            loading.setVisibility(View.GONE);
            noPopular.setVisibility(View.GONE);

            int layout;
            if (listView instanceof GridView || isSearchMode) {
                layout = R.layout.item_video;
            } else {
                layout = R.layout.item_video_compact;
            }

            adapter = new VideoAdapter(this, layout, videos);
            setAdapterForView(listView, adapter);

            if (subsFeed != null) {
                applyFeed(subsFeed);
                subsCount = Subscriptions.count();
            } else {
                updateSubsViews();
            }
        }
    }

    // ------------------------------------------------------------------
    // Local subscriptions feed
    // ------------------------------------------------------------------

    /**
     * Single source of truth for the Subscriptions tab UI state: the
     * previous list stays visible while a refresh runs, with the
     * NewPipe-style progress bar on top.
     */
    private void updateSubsViews() {
        View noSubs = findViewById(R.id.no_subs);
        TextView emptySubs = (TextView) findViewById(R.id.empty_subs);
        int count = Subscriptions.count();

        // 1. No subscriptions at all: show the "import" card.
        if (count == 0) {
            subsFeed = null;
            feedLoaded = false;
            feedError = false;
            hideFeedProgress();
            if (emptySubs != null) emptySubs.setVisibility(View.GONE);
            if (subsLastUpdate != null) subsLastUpdate.setVisibility(View.GONE);
            if (subsPull != null) subsPull.setVisibility(View.GONE);
            if (subsListView != null) subsListView.setVisibility(View.GONE);
            if (noSubs != null) noSubs.setVisibility(View.VISIBLE);
            return;
        }

        // 2. There are subscriptions: the pull gesture stays armed in every
        //    state (even over the error / empty captions).
        if (noSubs != null) noSubs.setVisibility(View.GONE);
        if (subsPull != null) subsPull.setVisibility(View.VISIBLE);
        if (subsListView != null) subsListView.setVisibility(View.VISIBLE);

        // 2a. Loading: updateFeedProgress drives the bar; the previous
        //     list stays visible when present.
        if (feedLoading) {
            if (emptySubs != null && (subsFeed == null || subsFeed.isEmpty())) {
                emptySubs.setVisibility(View.GONE);
            }
            if (subsLastUpdate != null) subsLastUpdate.setVisibility(View.GONE);
            return;
        }
        hideFeedProgress();

        // "Feed updated: X ago" caption (NewPipe's refresh subtitle).
        if (subsLastUpdate != null) {
            if (feedLoaded && feedLoadedAt > 0) {
                subsLastUpdate.setText(getString(R.string.feed_last_update,
                        Utils.formatTimeAgo(this, new Date(feedLoadedAt))));
                subsLastUpdate.setVisibility(View.VISIBLE);
            } else {
                subsLastUpdate.setVisibility(View.GONE);
            }
        }

        // 2b. Last attempt failed and nothing is cached: tappable error,
        //     with the pull gesture as an alternative retry.
        if (feedError && (subsFeed == null || subsFeed.isEmpty())) {
            if (emptySubs != null) {
                emptySubs.setText(R.string.feed_error);
                emptySubs.setVisibility(View.VISIBLE);
            }
            return;
        }

        // 2c. Feed ready.
        if (subsFeed != null && !subsFeed.isEmpty()) {
            if (emptySubs != null) emptySubs.setVisibility(View.GONE);
            return;
        }

        // 2d. Subscriptions exist but the feed is empty (channels without
        //     videos, or not loaded yet).
        if (emptySubs != null) {
            emptySubs.setText(R.string.no_videos);
            emptySubs.setVisibility(View.VISIBLE);
        }
    }

    /** True when the in-memory feed is older than FEED_STALE_MS (or its
     *  timestamp is unknown). */
    private boolean isFeedStale() {
        return feedLoadedAt <= 0
                || System.currentTimeMillis() - feedLoadedAt > FEED_STALE_MS;
    }

    /**
     * Decides whether an automatic refresh is due, per the configured mode:
     * ON OPEN refreshes on every fresh open (plus a staleness TTL),
     * MANUAL never refreshes on its own, INTERVAL refreshes once the
     * configured time has elapsed.
     */
    private void maybeAutoRefresh(boolean freshOpen) {
        if (isDestroyedFlag || feedLoading || Subscriptions.count() == 0) return;
        int mode = config.getFeedRefreshMode();
        if (mode == Config.FEED_REFRESH_MANUAL) {
            if (!feedLoaded && (subsFeed == null || subsFeed.isEmpty())) {
                startFeedLoad(FEED_REFRESH_AUTO);
            }
            return;
        }
        if (mode == Config.FEED_REFRESH_INTERVAL) {
            long interval = config.getFeedRefreshIntervalMinutes() * 60L * 1000L;
            if (feedLoaded && feedLoadedAt > 0
                    && System.currentTimeMillis() - feedLoadedAt < interval) {
                return;
            }
            startFeedLoad(FEED_REFRESH_AUTO);
            return;
        }
        // FEED_REFRESH_ON_OPEN (default, previous behavior)
        if (freshOpen || !feedLoaded || isFeedStale()) {
            startFeedLoad(FEED_REFRESH_AUTO);
        }
    }

    /** (Re)arm the interval-mode auto refresh timer (fires only while
     *  resumed, see onPause). */
    private void scheduleFeedTimer() {
        feedTimer.removeCallbacks(feedTimerTask);
        if (config.getFeedRefreshMode() == Config.FEED_REFRESH_INTERVAL
                && config.getFeedRefreshIntervalMinutes() > 0) {
            long interval = Math.max(60000L, config.getFeedRefreshIntervalMinutes() * 60L * 1000L);
            feedTimer.postDelayed(feedTimerTask, interval);
        }
    }

    /** Go back to the first tab unless we are already there. */
    private void resetToPopularTab() {
        if (hidePopular) return; // single-tab mode
        if (tabHost == null || tabHost.getCurrentTabTag() == null || "popular".equals(tabHost.getCurrentTabTag())) {
            return;
        }
        tabHost.setCurrentTab(0);
    }

    /** In the "hide Popular" mode search results borrow the popular
     *  tab's list, shown as an overlay above the subscriptions feed. */
    private void setSearchOverlayVisible(boolean visible) {
        if (!hidePopular) return;
        View popularTab = findViewById(R.id.popular_tab);
        View subsTab = findViewById(R.id.subs_tab);
        if (popularTab != null) popularTab.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (subsTab != null) subsTab.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    /** Start a feed refresh (idempotent). The refresh runs in the
     *  process-wide FeedManager coordinator, so it survives rotations. */
    private void startFeedLoad(int reason) {
        if (isDestroyedFlag) return;
        if (Subscriptions.count() == 0) {
            if (subsPull != null) subsPull.onRefreshComplete();
            return;
        }
        boolean ignoreThreshold = reason != FEED_REFRESH_AUTO;
        List<VideoInfo> current = reason == FEED_REFRESH_REBUILD ? null : subsFeed;
        boolean started = FeedManager.startUpdate(config, Subscriptions.get(), current,
                ignoreThreshold, reason);
        if (started) {
            feedLoading = true;
            feedError = false;
            updateSubsViews();
            updateFeedProgress(0, 0);
        } else if (FeedManager.isUpdating()) {
            // Already running: re-attach to the live progress.
            feedLoading = true;
            updateFeedProgress(FeedManager.getProgressCurrent(), FeedManager.getProgressTotal());
            updateSubsViews();
        } else {
            if (subsPull != null) subsPull.onRefreshComplete();
        }
    }

    /** Stop any in-flight feed refresh (e.g. the subscription list changed). */
    private void cancelFeedTask() {
        FeedManager.cancelUpdate();
        feedLoading = false;
        hideFeedProgress();
        if (subsPull != null) subsPull.onRefreshComplete();
    }

    /** NewPipe-style refresh progress: "Loading channels: X / Y". */
    private void updateFeedProgress(int current, int total) {
        if (!feedLoading) return;
        if (subsOverlay != null) subsOverlay.setVisibility(View.VISIBLE);
        if (subsProgress != null) {
            subsProgress.setVisibility(View.VISIBLE);
            if (total > 0) {
                subsProgress.setIndeterminate(false);
                subsProgress.setMax(total);
                subsProgress.setProgress(current);
            } else {
                subsProgress.setIndeterminate(true);
            }
        }
        if (subsProgressText != null) {
            subsProgressText.setVisibility(View.VISIBLE);
            subsProgressText.setText(total > 0
                    ? getString(R.string.feed_progress, current, total)
                    : getString(R.string.loading));
        }
    }

    private void hideFeedProgress() {
        if (subsOverlay != null) subsOverlay.setVisibility(View.GONE);
        if (subsProgress != null) subsProgress.setVisibility(View.GONE);
        if (subsProgressText != null) subsProgressText.setVisibility(View.GONE);
    }

    /**
     * Applies the result of a finished refresh (FeedManager observer,
     * already on the UI thread).
     */
    private void handleFeedResult(FeedManager.FeedUpdate r, int reason) {
        if (isDestroyedFlag) return;
        if (r != null) {
            if (r.seq != 0 && r.seq <= lastAppliedFeedSeq) return; // already applied
            lastAppliedFeedSeq = r.seq;
        }
        // A live observer applied it: drop the parked copy so a later
        // resume does not re-apply (idempotent for the pending path).
        FeedManager.clearPendingResult();
        feedLoading = false;
        hideFeedProgress();
        if (subsPull != null) subsPull.onRefreshComplete();
        if (r == null) {
            // No subscriptions: show the import card.
            feedLoaded = false;
            feedError = false;
            subsFeed = null;
            updateSubsViews();
            return;
        }
        // Nothing was outdated: the feed is up to date (NewPipe shows
        // its "feed is up to date" toast for manual refreshes).
        if (r.checked == 0) {
            if (reason == FEED_REFRESH_MANUAL) {
                Toast.makeText(MainActivity.this, R.string.feed_up_to_date, Toast.LENGTH_SHORT).show();
            }
            updateSubsViews();
            return;
        }
        if (r.videos == null || r.videos.isEmpty()) {
            // Nothing loaded and nothing cached: probably a connection
            // problem. Do NOT mark the feed as loaded so a retry happens.
            feedLoaded = false;
            feedError = true;
            subsFeed = null;
            updateSubsViews();
            return;
        }
        // At least one channel worked: show whatever we got.
        feedError = false;
        subsFeed = r.videos;
        feedLoaded = true;
        feedLoadedAt = r.loadedAt;
        applyFeed(r.videos);
        updateSubsViews();
        if (r.failed > 0 && reason == FEED_REFRESH_MANUAL) {
            showFailedChannels(r.failedNames);
        }
    }

    // ---- FeedManager.FeedObserver: called from the update thread ----

    public void onFeedProgress(final int current, final int total) {
        feedUiHandler.post(new Runnable() {
            public void run() {
                if (isDestroyedFlag) return;
                updateFeedProgress(current, total);
            }
        });
    }

    public void onFeedDone(final FeedManager.FeedUpdate result, final int reason) {
        feedUiHandler.post(new Runnable() {
            public void run() {
                if (isDestroyedFlag) return;
                handleFeedResult(result, reason);
            }
        });
    }

    /** Install the feed results into the Subscriptions tab list. */
    private void applyFeed(List<VideoInfo> feed) {
        if (subsListView == null || feed == null || feed.isEmpty()) return;
        subsAdapter = new VideoAdapter(this,
                subsListView instanceof GridView ? R.layout.item_video : R.layout.item_video_compact,
                feed);
        // Self-healing channel avatars: when the stored URL rotted (its
        // instance died), the adapter asks this listener for a live
        // re-resolution; the fresh URL is persisted to disk.
        subsAdapter.setChannelIconListener(new VideoAdapter.ChannelIconListener() {
            public String getResolvedIcon(String channelId) {
                return ChannelIconResolver.getResolved(channelId);
            }

            public void onRequestFallbackIcon(String channelId) {
                ChannelIconResolver.requestFallback(channelId,
                        new ChannelIconResolver.OnIconResolvedListener() {
                    public void onIconResolved(String cid, String url) {
                        if (url == null || url.length() == 0) return;
                        Subscriptions.setAvatar(cid, url);
                        if (subsAdapter != null && subsListView instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) subsListView;
                            for (int i = 0; i < vg.getChildCount(); i++) {
                                subsAdapter.updateChannelIconForView(vg.getChildAt(i), cid);
                            }
                        }
                    }
                });
            }
        });
        setAdapterForView(subsListView, subsAdapter);
    }

    /** Show which channels could not be updated (NewPipe's results dialog). */
    private void showFailedChannels(List<String> names) {
        if (names == null || names.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        int max = Math.min(names.size(), 12);
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append('\n');
            sb.append(names.get(i));
        }
        if (names.size() > max) sb.append('\n').append("...");
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.feed_failed_title)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String extractYouTubeId(String query) {
        String url = query.trim();
        int startIndex;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            startIndex = url.indexOf("://") + 3;
        } else {
            startIndex = 0;
            url = "http://" + url;
        }
        String host = url.substring(startIndex);
        int slashIndex = host.indexOf("/");
        String domain = slashIndex >= 0 ? host.substring(0, slashIndex) : host;
        String path = slashIndex >= 0 ? host.substring(slashIndex) : "";
        if (domain.endsWith("youtube.com") && path.startsWith("/watch")) {
            int vIndex = path.indexOf("v=");
            if (vIndex >= 0) {
                int ampIndex = path.indexOf("&", vIndex);
                return ampIndex >= 0 ? path.substring(vIndex + 2, ampIndex) : path.substring(vIndex + 2);
            }
        }
        if (domain.endsWith("youtu.be") && path.length() > 1) {
            int slash = path.indexOf("/", 1);
            return slash >= 0 ? path.substring(1, slash) : path.substring(1);
        }
        return null;
    }

    @Override
    public void onInstancesUpdated() {
        if (isDestroyedFlag) return;
        if (videos == null) {
            if (isSearchMode && searchQuery.getText().toString().trim().length() > 0) {
                findViewById(R.id.no_popular).setVisibility(View.GONE);
                findViewById(R.id.loading).setVisibility(View.VISIBLE);
                new SearchTask().execute(searchQuery.getText().toString().trim());
            } else if (hidePopular) {
                isSearchMode = false;
                // Popular hidden: nothing to load.
            } else {
                isSearchMode = false;
                new PopularTask().execute();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            if (isSearchMode) {
                isSearchMode = false;
                searchQuery.setText("");
                if (hidePopular) {
                    // Subs-only mode: hide the results overlay, the subs
                    // feed is the home screen.
                    setSearchOverlayVisible(false);
                    findViewById(R.id.no_popular).setVisibility(View.GONE);
                    findViewById(R.id.loading).setVisibility(View.GONE);
                    return true;
                }
                resetToPopularTab();
                findViewById(R.id.no_popular).setVisibility(View.GONE);
                findViewById(R.id.loading).setVisibility(View.VISIBLE);
                View emptyView = findViewById(R.id.empty_videos);
                if (emptyView != null) emptyView.setVisibility(View.GONE);
                Toast.makeText(this, R.string.confirm_exit, Toast.LENGTH_SHORT).show();
                new PopularTask().execute();
                return true;
            } else {
                finish();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchQuery != null) {
            imm.hideSoftInputFromWindow(searchQuery.getWindowToken(), 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (subsAdapter != null) {
            subsAdapter.notifyDataSetChanged();
        }
        int count = Subscriptions.count();
        boolean freshOpen = firstResume;
        firstResume = false;

        // Re-attach to the process-wide feed update first: it survived the
        // rotation.
        FeedManager.addObserver(this);
        if (FeedManager.isUpdating()) {
            feedLoading = true;
            updateFeedProgress(FeedManager.getProgressCurrent(), FeedManager.getProgressTotal());
        } else {
            feedLoading = false;
        }
        // A refresh that finished while no activity was attached left its
        // result parked in the coordinator: apply it now.
        FeedManager.FeedUpdate pending = FeedManager.takePendingResult();
        if (pending != null) {
            handleFeedResult(pending, FeedManager.takePendingReason());
        }

        if (subsCount != -1 && count != subsCount) {
            // The subscription list changed elsewhere: rebuild the feed so
            // removed channels disappear immediately.
            subsCount = count;
            cancelFeedTask();
            feedLoaded = false;
            feedError = false;
            subsFeed = null;
            if (count > 0) {
                startFeedLoad(FEED_REFRESH_REBUILD);
            } else {
                updateSubsViews();
            }
        } else if (count == 0 && feedLoaded) {
            subsCount = count;
            feedLoaded = false;
            subsFeed = null;
            updateSubsViews();
        } else if (skipFirstAutoRefresh) {
            // Rotation: the retained feed is already on screen.
            skipFirstAutoRefresh = false;
            subsCount = count;
            updateSubsViews();
        } else {
            subsCount = count;
            // Fresh app open / returning: decide per configured mode.
            maybeAutoRefresh(freshOpen);
            updateSubsViews();
        }
        scheduleFeedTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // The interval timer only fires while the activity is visible.
        feedTimer.removeCallbacks(feedTimerTask);
        // Detach from the update; the refresh itself keeps running in the
        // coordinator and its result waits in FeedManager for the next
        // attach.
        FeedManager.removeObserver(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (searchQuery != null) {
            outState.putString(STATE_SEARCH_QUERY, searchQuery.getText().toString());
        }
        outState.putBoolean(STATE_IS_SEARCH_MODE, isSearchMode);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        if (adapter != null) {
            try {
                super.onRestoreInstanceState(savedInstanceState);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyedFlag = true;
        feedTimer.removeCallbacks(feedTimerTask);
        FeedManager.removeObserver(this);
        // The in-flight update keeps running: its result waits in
        // FeedManager for the next attach.
        if (isFinishing()) {
            ImageLoader.clearCache();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return true;
        } else if (item.getItemId() == R.id.menu_refresh_feed) {
            // Manual refresh from the menu (works in every layout).
            if (Subscriptions.count() == 0) {
                Toast.makeText(this, R.string.no_subs, Toast.LENGTH_SHORT).show();
            } else {
                startFeedLoad(FEED_REFRESH_MANUAL);
            }
            if (tabHost != null && tabHost.getTabWidget().getTabCount() > 1
                    && !"subs".equals(tabHost.getCurrentTabTag())) {
                tabHost.setCurrentTab(1);
            }
            return true;
        } else if (item.getItemId() == R.id.menu_subscriptions) {
            startActivity(new Intent(this, SubscriptionsActivity.class));
            return true;
        } else if (item.getItemId() == R.id.menu_playlists) {
            startActivity(new Intent(this, PlaylistsActivity.class));
            return true;
        } else if (item.getItemId() == R.id.menu_about) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** MIT license of the bundled NNJSON library, shown in the About dialog. */
    private static final String MIT_LICENSE =
            "MIT License\n" +
            "\n" +
            "Copyright (c) 2021-2025 Arman Jussupgaliyev\n" +
            "\n" +
            "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files (the \"Software\"), to deal\n" +
            "in the Software without restriction, including without limitation the rights\n" +
            "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n" +
            "copies of the Software, and to permit persons to whom the Software is\n" +
            "furnished to do so, subject to the following conditions:\n" +
            "\n" +
            "The above copyright notice and this permission notice shall be included in all\n" +
            "copies or substantial portions of the Software.\n" +
            "\n" +
            "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n" +
            "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n" +
            "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n" +
            "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n" +
            "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n" +
            "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n" +
            "SOFTWARE.";

    /** About dialog: app name + version, icon and the fork credits. */
    private void showAboutDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(15, 15, 15, 15);

        TextView app = new TextView(this);
        app.setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME);
        app.setTypeface(null, Typeface.BOLD);
        app.setTextSize(20);
        app.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(app);

        ImageView imageView = new ImageView(this);
        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        imageView.setImageResource(R.drawable.main_icon);
        layout.addView(imageView);

        TextView text = new TextView(this);
        text.setText(getString(R.string.about_) + "\n\n" + MIT_LICENSE);
        layout.addView(text);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setView(scrollView)
                .setNeutralButton(android.R.string.ok, null).show();
    }

    private class SearchTask extends AsyncTask<String, Void, SearchResult> {
        @Override
        protected SearchResult doInBackground(String... params) {
            SearchResult result = new SearchResult();
            try {
                if (metadata != null) {
                    result.videos = metadata.search(params[0]);
                } else {
                    result.error = new Exception("Metadata client not initialized.");
                }
            } catch (Exception e) {
                result.error = e;
            }
            return result;
        }

        @Override
        protected void onPostExecute(SearchResult result) {
            if (isDestroyedFlag) return;
            View loadingView = findViewById(R.id.loading);
            if (loadingView != null) loadingView.setVisibility(View.GONE);
            TextView emptyView = (TextView) findViewById(R.id.empty_videos);
            if (result.error != null) {
                Toast.makeText(MainActivity.this, "Search failed: " + result.error.getMessage(), Toast.LENGTH_LONG).show();
                if (emptyView != null) emptyView.setVisibility(View.GONE);
            } else if (result.videos != null) {
                videos = result.videos;
                adapter = new VideoAdapter(MainActivity.this, R.layout.item_video, videos);
                if (listView != null) {
                    setAdapterForView(listView, adapter);
                }
                if (emptyView != null) {
                    if (videos == null || videos.isEmpty()) {
                        emptyView.setVisibility(View.VISIBLE);
                    } else {
                        emptyView.setVisibility(View.GONE);
                    }
                }
            }
        }
    }

    private class SearchResult {
        List<VideoInfo> videos;
        Exception error;
    }

    private class PopularTask extends AsyncTask<Void, Void, PopularResult> {
        @Override
        protected PopularResult doInBackground(Void... params) {
            PopularResult result = new PopularResult();
            try {
                Metadata popularApi = null;
                try {
                    popularApi = Manager.getInstance().getPopularMetadata();
                } catch (Exception ignored) {}
                if (popularApi != null) {
                    try {
                        result.videos = popularApi.getPopularVideos();
                        // An empty list is not a usable feed: fall through.
                        if (result.videos != null && !result.videos.isEmpty()) return result;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (metadata != null) {
                    result.videos = metadata.getPopularVideos();
                } else {
                    result.error = new Exception("Popular unavailable");
                }
            } catch (Exception e) {
                e.printStackTrace();
                result.error = e;
            }
            return result;
        }

        @Override
        protected void onPostExecute(PopularResult result) {
            if (isDestroyedFlag) return;
            View loadingView = findViewById(R.id.loading);
            if (loadingView != null) loadingView.setVisibility(View.GONE);
            View noPopularView = findViewById(R.id.no_popular);
            TextView emptyView = (TextView) findViewById(R.id.empty_videos);
            if (emptyView != null) emptyView.setVisibility(View.GONE);
            if (result.error != null) {
                if (noPopularView != null) noPopularView.setVisibility(View.VISIBLE);
                result.error.printStackTrace();
            } else if (metadata == null || result.videos == null || result.videos.size() == 0) {
                if (noPopularView != null) noPopularView.setVisibility(View.VISIBLE);
            } else {
                if (noPopularView != null) noPopularView.setVisibility(View.GONE);
                videos = result.videos;
                int layout = result.videos.get(0).channelThumbnail.length() > 0 ? R.layout.item_video : R.layout.item_video_compact;
                adapter = new VideoAdapter(context, layout, videos);
                if (listView != null) {
                    setAdapterForView(listView, adapter);
                }
            }
        }
    }

    private class PopularResult {
        List<VideoInfo> videos;
        Exception error;
    }
}
