package io.github.gohoski.notpipe;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.api.Metadata;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.ui.VideoAdapter;
import io.github.gohoski.notpipe.util.ImageLoader;

/**
 * Videos of one playlist. Two sources share this screen:
 *  - a channel's public playlist (EXTRA_ID is the PL id; videos are loaded
 *    through the metadata frontend with fallback);
 *  - a local playlist (EXTRA_ID is "local:<name>"; ids come from the
 *    Playlists store and are resolved to metadata rows one by one).
 */
public class ChannelPlaylistActivity extends Activity {

    public static final String EXTRA_ID = "ID";
    public static final String EXTRA_NAME = "NAME";

    private String playlistId;
    private String playlistName;
    private ListView list;
    private View loading;
    private TextView empty;
    private VideoAdapter adapter;
    private List<VideoInfo> videos = new ArrayList<VideoInfo>();
    private boolean isDestroyedFlag = false;
    private LoadPlaylistTask task;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_playlist);

        playlistId = getIntent().getStringExtra(EXTRA_ID);
        playlistName = getIntent().getStringExtra(EXTRA_NAME);
        if (playlistName != null && playlistName.length() > 0) {
            setTitle(playlistName);
        }

        list = (ListView) findViewById(R.id.playlist_videos);
        loading = findViewById(R.id.playlist_loading);
        empty = (TextView) findViewById(R.id.no_playlist_videos);

        adapter = new VideoAdapter(this, R.layout.item_video_compact, videos);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Intent intent = new Intent(ChannelPlaylistActivity.this, VideoActivity.class);
                intent.putExtra("ID", videos.get(position).id);
                startActivity(intent);
            }
        });

        task = new LoadPlaylistTask();
        task.execute(playlistId);
    }

    private class LoadPlaylistTask extends AsyncTask<String, Void, List<VideoInfo>> {
        @Override
        protected List<VideoInfo> doInBackground(String... params) {
            String id = params[0];
            try {
                if (id != null && id.startsWith("local:")) {
                    // Local playlist: resolve each stored id to a metadata
                    // row (title/thumbnail) through the frontend.
                    List<String> ids = Playlists.getVideos(id.substring(6));
                    List<VideoInfo> result = new ArrayList<VideoInfo>();
                    Metadata api = null;
                    try {
                        api = Manager.getInstance().getMetadata();
                    } catch (Exception ignored) {
                    }
                    for (int i = 0; i < ids.size(); i++) {
                        if (api == null) break;
                        try {
                            VideoInfo v = api.getVideo(ids.get(i));
                            if (v != null) result.add(v);
                        } catch (Exception ignored) {
                            // Skip an unresolvable video, keep the rest.
                        }
                    }
                    return result;
                }
                Metadata api = Manager.getInstance().getMetadata();
                return api.getPlaylistVideos(id);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        @Override
        protected void onPostExecute(List<VideoInfo> result) {
            if (isCancelled() || isDestroyedFlag) return;
            loading.setVisibility(View.GONE);
            if (result == null || result.isEmpty()) {
                empty.setVisibility(View.VISIBLE);
                list.setVisibility(View.GONE);
                return;
            }
            videos.clear();
            videos.addAll(result);
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyedFlag = true;
        if (task != null) {
            task.cancel(true);
        }
        if (isFinishing()) {
            ImageLoader.clearCache();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }
}
