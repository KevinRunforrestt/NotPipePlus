package io.github.gohoski.notpipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.List;

import io.github.gohoski.notpipe.api.ChannelApi;
import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.api.ContentUnavailableException;
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.ChannelPlaylist;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.ui.VideoAdapter;
import io.github.gohoski.notpipe.util.ConnectivityMonitor;
import io.github.gohoski.notpipe.util.ImageLoader;

/**
 * Channel page, NewPipe-style tabs: "Popular" (this channel's most
 *  popular videos, default), "Videos" (latest uploads) and "Playlists"
 *  (public playlists, lazy). The header carries the subscribe button in
 *  both orientations.
 */
public class ChannelActivity extends Activity implements ConnectivityMonitor.Listener {
    Metadata api;
    ChannelApi channelApi;
    String channelId;
    Channel channel;
    LinearLayout channelLayout;
    Context context;
    ImageView banner;
    boolean isDestroyedFlag = false;

    TabHost tabHost;
    /** List of the "Popular" tab; the layout ids keep the historical
     *  for_you names, the content is the channel's most popular videos. */
    AbsListView forYouList;
    AbsListView videosList;
    VideoAdapter forYouAdapter;
    VideoAdapter videosAdapter;
    boolean popularLoading = false;
    boolean videosLoading = false;

    // NewPipe-style subscribe button in the channel header.
    Button subscribeButton;

    List<VideoInfo> latestVideos = null;
    List<VideoInfo> popularVideos = null;

    // Public playlists created by the channel ("Playlists" tab)
    List<ChannelPlaylist> channelPlaylists = null;
    BaseAdapter playlistsAdapter;
    boolean playlistsLoaded = false;
    boolean playlistsLoading = false;
    LoadPlaylistsTask loadPlaylistsTask;
    // Kept so onDestroy can cancel in-flight loads: on rotation the old
    // activity is destroyed and Gingerbread runs all AsyncTasks on one
    // serial thread — an uncancelled task keeps it busy for nothing.
    LoadChannelTask loadChannelTask;
    LoadVideosTask loadVideosTask;
    LoadPopularVideosTask loadPopularVideosTask;

    // ---- Rotation support (android:configChanges) ----
    // The activity survives rotation: onConfigurationChanged swaps the
    // layout and re-binds what is already in memory (no new requests).
    boolean tabsSetUp = false;        // setupTabs already ran for this instance
    String currentTabTag = null;    // last selected tab
    /** Hops worker-thread results back to the UI thread. */
    private final Handler mainHandler = new Handler();

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
        setContentView(R.layout.activity_channel);

        channelId = getIntent().getStringExtra("ID");
        channelLayout = (LinearLayout) findViewById(R.id.channel);
        banner = (ImageView) findViewById(R.id.banner);
        context = this;

        bindChannelHeaderListener();
        bindSubscribeButton();

        // Proxies are cheap to build (no I/O). Creating them up front
        // lets the popular prefetch below run beside the channel load.
        try {
            api = Manager.getInstance().getMetadata();
            channelApi = Manager.getInstance().getChannelApi();
        } catch (IllegalStateException ignored) {}

        loadChannelTask = new LoadChannelTask();
        loadChannelTask.execute(channelId);
        // The header and the default "Popular" tab cost one request each:
        // a raw thread runs them side by side, because Gingerbread would
        // serialize two AsyncTasks onto one worker (halves the wait).
        startPopularPrefetch();
    }

    /** Channel header tap -> dialog with subscriber count + description. */
    private void bindChannelHeaderListener() {
        if (channelLayout == null) return;
        channelLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (channel == null) return;

                TextView text = new TextView(context);
                text.setText(getString(R.string.subscribers, NumberFormat.getNumberInstance().format(channel.subscriberCount))
                        + "\n\n" + channel.description);
                text.setPadding(15,15,15,15);

                ScrollView scroll = new ScrollView(context);
                scroll.addView(text);

                new AlertDialog.Builder(context).setTitle(channel.title).setView(scroll).show();
            }
        });
    }

    /** Header subscribe/unsubscribe button, present in both layouts. */
    private void bindSubscribeButton() {
        subscribeButton = (Button) findViewById(R.id.subscribe_button);
        if (subscribeButton != null) {
            subscribeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (channel == null || channel.id == null || channel.id.length() == 0) return;
                    String name = (channel.title == null || channel.title.length() == 0)
                            ? channel.id : channel.title;
                    if (Subscriptions.isSubscribed(channel.id)) {
                        Subscriptions.unsubscribe(channel.id);
                        Toast.makeText(context, getString(R.string.unsubscribed_toast, name), Toast.LENGTH_SHORT).show();
                    } else {
                        Subscriptions.subscribe(channel.id, name, channel.thumbnail);
                        Toast.makeText(context, getString(R.string.subscribed_toast, name), Toast.LENGTH_SHORT).show();
                    }
                    updateSubscribeButton();
                }
            });
        }
    }

    /**
     * Rotation does not destroy this activity (android:configChanges).
     * Here we simply swap in the layout matching the new orientation and
     * re-bind what we already have in memory. Zero new network requests.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (channelId == null) return; // not initialized yet, nothing to re-bind
        rebindAfterRotation();
    }

    private void rebindAfterRotation() {
        String previousTab = currentTabTag;

        // Fresh view tree: the system resolves portrait phone layout vs the
        // tablet layout-large-land variant for the new orientation.
        setContentView(R.layout.activity_channel);

        channelLayout = (LinearLayout) findViewById(R.id.channel);
        banner = (ImageView) findViewById(R.id.banner);
        bindChannelHeaderListener();
        bindSubscribeButton();

        View loading = findViewById(R.id.loading);
        if (channel != null) {
            if (loading != null) loading.setVisibility(View.GONE);
            ((TextView) findViewById(R.id.title)).setText(channel.title);
            ((TextView) findViewById(R.id.subscribers)).setText(getString(R.string.subscribers, Utils.formatNumber(context, channel.subscriberCount)));
            ((TextView) findViewById(R.id.description)).setText(channel.description);
            ImageLoader.loadImage(channel.thumbnail, ((ImageView) findViewById(R.id.thumbnail)), false, null, 200);
            ImageLoader.loadImage(channel.banner, banner, false, null, 480);
            channelLayout.setVisibility(View.VISIBLE);
            if (banner != null) {
                banner.setVisibility(View.VISIBLE);
            }
            updateSubscribeButton();
        } else if (loading != null) {
            loading.setVisibility(View.VISIBLE);
        }

        if (!tabsSetUp) {
            // Channel metadata still loading: the in-flight task's callbacks
            // findViewById the fresh views and set everything up when done.
            return;
        }

        setupTabs();

        // setupTabs leaves the fresh TabHost at layout-default visibility.
        if (tabHost != null) {
            tabHost.setVisibility(View.VISIBLE);
        }

        // Restore the playlists tab content even when it is not the
        // visible tab, so switching to it later shows the data instead of
        // a stale spinner.
        if (playlistsLoading) {
            // nothing to do
        } else if (playlistsLoaded) {
            displayPlaylists(channelPlaylists);
        } else {
            displayPlaylists(null);
        }

        // Put the user back on the tab they were viewing.
        if (previousTab != null && tabHost != null) {
            tabHost.setCurrentTabByTag(previousTab);
        }
    }

    /** Updates the header subscribe button: "Subscribe"/"Subscribed". */
    private void updateSubscribeButton() {
        if (subscribeButton == null || channel == null || channel.id == null || channel.id.length() == 0) return;
        boolean subscribed = Subscriptions.isSubscribed(channel.id);
        // Native look: the state shows through the label swap alone.
        subscribeButton.setText(subscribed ? R.string.subscribed_button : R.string.subscribe);
    }

    /**
     * Builds the channel tabs: Popular (default) / Videos / Playlists.
     * Each tab binds what is in memory; missing data loads lazily,
     * guarded by a flag so a rotation rebind never starts a duplicate
     * request.
     */
    private void setupTabs() {
        tabHost = (TabHost) findViewById(android.R.id.tabhost);
        tabHost.setup();

        // "Popular" tab: this channel's most popular videos (the default).
        tabHost.addTab(tabHost.newTabSpec("popular")
                .setIndicator(getString(R.string.popular))
                .setContent(R.id.for_you));

        // "Videos" tab: latest uploads of this channel.
        tabHost.addTab(tabHost.newTabSpec("videos")
                .setIndicator(getString(R.string.videos))
                .setContent(R.id.videos));

        // NewPipe-style "Playlists" tab: the public playlists created by
        // this YouTuber, loaded lazily on first visit. Registered only
        // when the inflated layout has the tab (addTab throws for a
        // missing content view).
        ListView playlistsList = null;
        if (findViewById(R.id.playlists) != null) {
            tabHost.addTab(tabHost.newTabSpec("playlists").setIndicator(getString(R.string.playlists)).setContent(R.id.playlists));
            playlistsList = (ListView) findViewById(R.id.playlists_list);
        }
        if (playlistsList != null) {
            playlistsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    // No clearCache/gc on click (freezes weak devices).
                    if (channelPlaylists == null || position >= channelPlaylists.size()) return;
                    ChannelPlaylist pl = channelPlaylists.get(position);
                    Intent intent = new Intent(ChannelActivity.this, ChannelPlaylistActivity.class);
                    intent.putExtra(ChannelPlaylistActivity.EXTRA_ID, pl.id);
                    intent.putExtra(ChannelPlaylistActivity.EXTRA_NAME, pl.title);
                    startActivity(intent);
                }
            });
        }
        TextView noPlaylistsView = (TextView) findViewById(R.id.no_channel_playlists);
        if (noPlaylistsView != null) {
            noPlaylistsView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Tap on the error/empty text retries (error case only).
                    if (!playlistsLoaded && !playlistsLoading) {
                        loadChannelPlaylists();
                    }
                }
            });
        }

        // Bind whatever is already in memory (rotation rebind), the tabs
        // fetch lazily otherwise.
        bindPopularTab();
        bindVideosTab();

        tabHost.setOnTabChangedListener(new TabHost.OnTabChangeListener() {
            @Override
            public void onTabChanged(String tabId) {
                currentTabTag = tabId;
                if ("popular".equals(tabId)) {
                    // Popular-by-default: load on first visit if not ready.
                    if (popularVideos == null && !popularLoading) {
                        startPopularVideosLoad();
                    }
                } else if ("videos".equals(tabId)) {
                    if (latestVideos == null && !videosLoading) {
                        startVideosLoad();
                    }
                } else if ("playlists".equals(tabId)) {
                    // Lazy: one request when the user opens the tab.
                    if (!playlistsLoaded && !playlistsLoading) {
                        loadChannelPlaylists();
                    }
                }
            }
        });

        if (NotPipe.SDK < 11) {
            int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 33f, getResources().getDisplayMetrics());
            TabWidget widget = tabHost.getTabWidget();
            for (int i = 0; i < widget.getChildCount(); i++) {
                View child = widget.getChildAt(i);
                if (child != null && child.getLayoutParams() != null) {
                    child.getLayoutParams().height = height;
                }
            }
        }

        tabsSetUp = true;
    }

    /** Shows the Popular tab content from memory, or kicks its first load. */
    private void bindPopularTab() {
        if (popularVideos != null) {
            displayPopular(popularVideos);
        } else if (!popularLoading) {
            startPopularVideosLoad();
        } else {
            // still loading: keep the spinner, the task's callback binds.
        }
    }

    /** Shows the Videos tab content from memory, or arms its lazy load. */
    private void bindVideosTab() {
        if (latestVideos != null) {
            displayVideos(latestVideos);
        }
        // null: the spinner stays; the tab-change listener loads on visit.
    }

    /** Kicks off the background load of this channel's popular videos. */
    private void startPopularVideosLoad() {
        if (popularLoading || channelId == null || channelId.length() == 0) return;
        popularLoading = true;
        loadPopularVideosTask = new LoadPopularVideosTask();
        loadPopularVideosTask.execute(channelId);
    }

    /** Same request as the Popular tab, but on its own worker thread so
     *  it races the channel metadata instead of queueing behind it.
     *  Until the tabs are set up the result only parks itself in
     *  popularVideos; setupTabs() picks it up when the header is ready. */
    private void startPopularPrefetch() {
        if (popularLoading || channelId == null || channelId.length() == 0) return;
        popularLoading = true;
        new Thread(new Runnable() {
            public void run() {
                final List<VideoInfo> result = fetchChannelVideos(channelId, 1);
                mainHandler.post(new Runnable() {
                    public void run() {
                        if (isDestroyedFlag) return;
                        popularLoading = false;
                        popularVideos = result;
                        if (tabsSetUp) {
                            displayPopular(result);
                        }
                    }
                });
            }
        }, "ch-popular").start();
    }

    /** getChannelVideos with the ChannelApi fallback, shared by the
     *  Popular and Videos tabs (sort: 1 = popular, 0 = latest). */
    private List<VideoInfo> fetchChannelVideos(String id, int sort) {
        List<VideoInfo> result = null;
        try {
            if (api != null) result = api.getChannelVideos(id, sort);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if ((result == null || result.isEmpty()) && channelApi != null) {
            try {
                List<VideoInfo> alt = channelApi.getChannelVideos(id, sort);
                if (alt != null && !alt.isEmpty()) result = alt;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return result;
    }

    /** Kicks off the background load of this channel's latest uploads. */
    private void startVideosLoad() {
        if (videosLoading || channelId == null || channelId.length() == 0) return;
        videosLoading = true;
        loadVideosTask = new LoadVideosTask();
        loadVideosTask.execute(channelId);
    }

    /** Kicks off the background load of the channel's public playlists. */
    private void loadChannelPlaylists() {
        if (playlistsLoading || channelId == null || channelId.length() == 0) return;
        playlistsLoading = true;
        View loading = findViewById(R.id.playlists_loading);
        if (loading != null) loading.setVisibility(View.VISIBLE);
        TextView noPlaylists = (TextView) findViewById(R.id.no_channel_playlists);
        if (noPlaylists != null) noPlaylists.setVisibility(View.GONE);
        ListView playlistsList = (ListView) findViewById(R.id.playlists_list);
        if (playlistsList != null) playlistsList.setVisibility(View.GONE);
        loadPlaylistsTask = new LoadPlaylistsTask();
        loadPlaylistsTask.execute(channelId);
    }

    /**
     * Installs the playlists results: list, or the empty/error text.
     * @param playlists fetched list (empty = none, null = error)
     */
    private void displayPlaylists(List<ChannelPlaylist> playlists) {
        View loading = findViewById(R.id.playlists_loading);
        if (loading != null) loading.setVisibility(View.GONE);
        TextView noPlaylists = (TextView) findViewById(R.id.no_channel_playlists);
        ListView playlistsList = (ListView) findViewById(R.id.playlists_list);

        if (playlists != null && !playlists.isEmpty()) {
            channelPlaylists = playlists;
            playlistsAdapter = new PlaylistAdapter();
            if (playlistsList != null) {
                playlistsList.setAdapter(playlistsAdapter);
                playlistsList.setVisibility(View.VISIBLE);
            }
            if (noPlaylists != null) noPlaylists.setVisibility(View.GONE);
        } else {
            if (playlistsList != null) playlistsList.setVisibility(View.GONE);
            if (noPlaylists != null) {
                noPlaylists.setText(playlists == null
                        ? R.string.channel_playlists_error
                        : R.string.no_channel_playlists);
                noPlaylists.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Row adapter for the public playlists of a channel (cheap,
     * ViewHolder-less: a handful of visible rows).
     */
    private class PlaylistAdapter extends BaseAdapter {
        private LayoutInflater inflater;

        PlaylistAdapter() {
            inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        public int getCount() {
            return channelPlaylists == null ? 0 : channelPlaylists.size();
        }

        public Object getItem(int position) {
            return channelPlaylists.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_channel_playlist, parent, false);
            }
            ChannelPlaylist pl = channelPlaylists.get(position);
            ((TextView) convertView.findViewById(R.id.playlist_title)).setText(pl.title);
            TextView count = (TextView) convertView.findViewById(R.id.playlist_count);
            if (pl.videoCount >= 0) {
                count.setText(getString(R.string.videos_count, pl.videoCount));
            } else {
                count.setText("");
            }
            ImageView thumb = (ImageView) convertView.findViewById(R.id.playlist_thumbnail);
            // Re-request only when the recycled row was bound to another
            // playlist (avoids the load-flash while scrolling).
            String currentTag = (String) thumb.getTag();
            if (pl.thumbnail != null && pl.thumbnail.length() > 0) {
                if (!pl.thumbnail.equals(currentTag)) {
                    thumb.setImageResource(android.R.drawable.ic_menu_slideshow);
                    ImageLoader.loadImage(pl.thumbnail, thumb, true, null, 200);
                }
            } else if (!"generic".equals(currentTag)) {
                thumb.setImageResource(android.R.drawable.ic_menu_slideshow);
                thumb.setTag("generic");
            }
            return convertView;
        }
    }

    private class LoadPlaylistsTask extends AsyncTask<String, Void, List<ChannelPlaylist>> {
        @Override
        protected List<ChannelPlaylist> doInBackground(String... strings) {
            String id = strings[0];
            try {
                return api.getChannelPlaylists(id);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<ChannelPlaylist> result) {
            if (isCancelled() || isDestroyedFlag) return;
            playlistsLoading = false;
            playlistsLoaded = (result != null);
            displayPlaylists(result);
        }
    }

    /** Installs the Popular tab results (or its error/empty caption). */
    private void displayPopular(final List<VideoInfo> fetchedVideos) {
        View loading = findViewById(R.id.for_you_loading);
        if (loading != null) loading.setVisibility(View.GONE);
        TextView noPopular = (TextView) findViewById(R.id.no_channel_popular);
        forYouList = (AbsListView) findViewById(R.id.for_you_list);
        if (noPopular != null) {
            if (fetchedVideos == null || fetchedVideos.isEmpty()) {
                noPopular.setVisibility(View.VISIBLE);
            } else {
                noPopular.setVisibility(View.GONE);
            }
        }
        if (forYouList != null) {
            if (fetchedVideos == null || fetchedVideos.isEmpty()) {
                forYouList.setVisibility(View.GONE);
            } else {
                forYouList.setVisibility(View.VISIBLE);
                forYouAdapter = new VideoAdapter(context, R.layout.item_video_compact, fetchedVideos, true);
                setAdapterForView(forYouList, forYouAdapter);
                forYouList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        Intent intent = new Intent(ChannelActivity.this, VideoActivity.class);
                        intent.putExtra("ID", fetchedVideos.get(position).id);
                        startActivity(intent);
                    }
                });
            }
        }
    }

    /** Installs the Videos (latest) tab results. */
    private void displayVideos(final List<VideoInfo> fetchedVideos) {
        findViewById(R.id.videos_loading).setVisibility(View.GONE);
        TextView noVideosView = (TextView) findViewById(R.id.no_videos);
        videosList = (AbsListView) findViewById(R.id.videos_list);
        videosList.setVisibility(View.VISIBLE);
        if (noVideosView != null) {
            if (fetchedVideos == null || fetchedVideos.isEmpty()) {
                noVideosView.setVisibility(View.VISIBLE);
            } else {
                noVideosView.setVisibility(View.GONE);
            }
        }
        if (fetchedVideos != null) {
            videosAdapter = new VideoAdapter(context, R.layout.item_video_compact, fetchedVideos, true);
            setAdapterForView(videosList, videosAdapter);
            videosList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    Intent intent = new Intent(ChannelActivity.this, VideoActivity.class);
                    intent.putExtra("ID", fetchedVideos.get(position).id);
                    startActivity(intent);
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload this page's failed loads once the network is back.
        ConnectivityMonitor.register(this);
        if (forYouAdapter != null) {
            forYouAdapter.notifyDataSetChanged();
        }
        if (videosAdapter != null) {
            videosAdapter.notifyDataSetChanged();
        }
        if (channel != null && banner != null) {
            // Banners are wide but shown at screen width: 480px decode
            // keeps it sharp while sparing the heap.
            ImageLoader.loadImage(channel.banner, banner, true, null, 480);
        }
        // The subscription state may have changed elsewhere — keep the
        // button in sync.
        updateSubscribeButton();
    }

    /** ConnectivityMonitor: the network was down and is back. Retries
     *  the loads that ended in an error; finished screens stay as they
     *  are. */
    public void onConnectionRestored() {
        if (isDestroyedFlag || channelId == null) return;
        // The whole page died (no channel header): full retry, popular
        // prefetch included.
        if (channel == null) {
            if (loadChannelTask == null
                    || loadChannelTask.getStatus() != AsyncTask.Status.FINISHED) {
                return; // still running or queued: let it finish
            }
            View loading = findViewById(R.id.loading);
            if (loading != null) loading.setVisibility(View.VISIBLE);
            loadChannelTask = new LoadChannelTask();
            loadChannelTask.execute(channelId);
            startPopularPrefetch();
            return;
        }
        // Header is fine: only the list that is shown and broken retries.
        if (tabsSetUp && "popular".equals(currentTabTag)
                && popularVideos == null && !popularLoading) {
            startPopularVideosLoad();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConnectivityMonitor.unregister(this);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (NotPipe.SDK < 11) {
            getMenuInflater().inflate(R.menu.menu_channel, menu);
            return true;
        }
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (NotPipe.SDK < 11) {
            MenuItem subscribeItem = menu.findItem(R.id.action_subscribe);
            if (subscribeItem != null) {
                if (channel == null || channel.id == null || channel.id.length() == 0) {
                    subscribeItem.setVisible(false);
                } else {
                    subscribeItem.setVisible(true);
                    subscribeItem.setTitle(Subscriptions.isSubscribed(channel.id) ? R.string.unsubscribe : R.string.subscribe);
                }
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (NotPipe.SDK < 11) {
            if (item.getItemId() == R.id.action_subscribe) {
                if (channel != null && channel.id != null && channel.id.length() != 0) {
                    String channelId = channel.id;
                    String name = (channel.title == null || channel.title.length() == 0) ? channelId : channel.title;
                    if (Subscriptions.isSubscribed(channelId)) {
                        Subscriptions.unsubscribe(channelId);
                        Toast.makeText(this, getString(R.string.unsubscribed_toast, name), Toast.LENGTH_SHORT).show();
                    } else {
                        Subscriptions.subscribe(channelId, name, channel.thumbnail);
                        Toast.makeText(this, getString(R.string.subscribed_toast, name), Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyedFlag = true;
        if (isFinishing()) {
            ImageLoader.clearCache();
        }
        if (loadPlaylistsTask != null) {
            loadPlaylistsTask.cancel(true);
            loadPlaylistsTask = null;
        }
        if (loadChannelTask != null) {
            loadChannelTask.cancel(true);
            loadChannelTask = null;
        }
        if (loadVideosTask != null) {
            loadVideosTask.cancel(true);
            loadVideosTask = null;
        }
        if (loadPopularVideosTask != null) {
            loadPopularVideosTask.cancel(true);
            loadPopularVideosTask = null;
        }
        if (banner != null) {
            banner.setImageDrawable(null);
        }
    }

    private class LoadChannelTask extends AsyncTask<String, Void, Channel> {
        private boolean contentUnavailable;

        @Override
        protected Channel doInBackground(String... strings) {
            try {
                if (isCancelled()) return null;
                if (api == null) return null;
                return api.getChannel(strings[0]);
            } catch (ContentUnavailableException e) {
                e.printStackTrace();
                contentUnavailable = true;
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(final Channel fetched) {
            if (isCancelled() || isDestroyedFlag) return;
            if (fetched == null) {
                findViewById(R.id.loading).setVisibility(View.GONE);
                if (contentUnavailable) {
                    Toast.makeText(context, R.string.content_unavailable, Toast.LENGTH_SHORT).show();
                }
                return;
            }
            channel = fetched;
            channelId = channel.id;

            ((TextView) findViewById(R.id.title)).setText(channel.title);
            ((TextView) findViewById(R.id.subscribers)).setText(getString(R.string.subscribers, Utils.formatNumber(context, channel.subscriberCount)));
            ((TextView) findViewById(R.id.description)).setText(channel.description);
            ImageLoader.loadImage(channel.thumbnail, ((ImageView) findViewById(R.id.thumbnail)), false, null, 200);
            ImageLoader.loadImage(channel.banner, banner, false, null, 480);

            // Reflect the current subscription state in the header button.
            updateSubscribeButton();

            // Immediately make the channel metadata and banner visible as requested
            channelLayout.setVisibility(View.VISIBLE);
            if (banner != null) {
                banner.setVisibility(View.VISIBLE);
            }

            // The channel payload carries the LATEST videos; they feed the
            // "Videos" tab without an extra request. The default "Popular"
            // tab costs exactly one request on first paint.
            latestVideos = channel.videos;

            findViewById(R.id.loading).setVisibility(View.GONE);
            setupTabs();
            if (tabHost != null) {
                tabHost.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * The most popular videos of THIS channel (sort=popular with the
     * ChannelApi as fallback), shown in the default "Popular" tab.
     */
    private class LoadPopularVideosTask extends AsyncTask<String, Void, List<VideoInfo>> {
        @Override
        protected void onPreExecute() {
            View loading = findViewById(R.id.for_you_loading);
            if (loading != null) loading.setVisibility(View.VISIBLE);
            AbsListView list = (AbsListView) findViewById(R.id.for_you_list);
            if (list != null) list.setVisibility(View.GONE);
            TextView noPopular = (TextView) findViewById(R.id.no_channel_popular);
            if (noPopular != null) noPopular.setVisibility(View.GONE);
        }

        @Override
        protected List<VideoInfo> doInBackground(String... strings) {
            if (api == null && channelApi == null) return null;
            return fetchChannelVideos(strings[0], 1);
        }

        @Override
        protected void onPostExecute(List<VideoInfo> fetchedVideos) {
            if (isCancelled() || isDestroyedFlag) return;
            popularLoading = false;
            popularVideos = fetchedVideos;
            displayPopular(fetchedVideos);
        }
    }

    /** The latest uploads of this channel (the "Videos" tab). */
    private class LoadVideosTask extends AsyncTask<String, Void, List<VideoInfo>> {
        @Override
        protected void onPreExecute() {
            findViewById(R.id.videos_loading).setVisibility(View.VISIBLE);
            AbsListView vList = (AbsListView) findViewById(R.id.videos_list);
            if (vList != null) {
                vList.setVisibility(View.GONE);
            }
            View noVideosView = findViewById(R.id.no_videos);
            if (noVideosView != null) {
                noVideosView.setVisibility(View.GONE);
            }
        }

        @Override
        protected List<VideoInfo> doInBackground(String... strings) {
            if (api == null && channelApi == null) return null;
            return fetchChannelVideos(strings[0], 0);
        }

        @Override
        protected void onPostExecute(final List<VideoInfo> fetchedVideos) {
            if (isCancelled() || isDestroyedFlag) return;
            videosLoading = false;
            latestVideos = fetchedVideos;
            displayVideos(fetchedVideos);
        }
    }
}
