package io.github.gohoski.notpipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.github.gohoski.notpipe.util.ImageLoader;

/**
 * Local playlists manager: lists the playlists created on this device.
 * Tap a playlist to open its videos (VideoActivity-style list); the menu
 * creates a new one; long-press deletes. Videos are added from any video
 * page.
 */
public class PlaylistsActivity extends Activity {

    private ListView list;
    private TextView empty;
    private List<String> names;
    private PlaylistsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlists);

        list = (ListView) findViewById(R.id.playlists_list);
        empty = (TextView) findViewById(R.id.empty_playlists);

        names = Playlists.getNames();
        adapter = new PlaylistsAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String name = names.get(position);
                Intent intent = new Intent(PlaylistsActivity.this, ChannelPlaylistActivity.class);
                intent.putExtra(ChannelPlaylistActivity.EXTRA_ID, "local:" + name);
                intent.putExtra(ChannelPlaylistActivity.EXTRA_NAME, name);
                startActivity(intent);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                final String name = names.get(position);
                new AlertDialog.Builder(PlaylistsActivity.this)
                        .setTitle(R.string.delete_playlist_q)
                        .setMessage(name)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                Playlists.delete(name);
                                refresh();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        names = Playlists.getNames();
        if (names.isEmpty()) {
            empty.setText(R.string.no_playlists);
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_playlists, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_new_playlist) {
            newPlaylistDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void newPlaylistDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.playlist_name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_playlist)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) return;
                        if (Playlists.create(name)) {
                            refresh();
                        } else {
                            Toast.makeText(PlaylistsActivity.this, R.string.playlist_name,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    private class PlaylistsAdapter extends BaseAdapter {
        private LayoutInflater inflater;

        PlaylistsAdapter() {
            inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        }

        public int getCount() {
            return names.size();
        }

        public Object getItem(int position) {
            return names.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_playlist, parent, false);
            }
            String name = names.get(position);
            ((TextView) convertView.findViewById(R.id.playlist_title)).setText(name);
            ((TextView) convertView.findViewById(R.id.playlist_count))
                    .setText(getString(R.string.videos_count, Playlists.getVideos(name).size()));
            return convertView;
        }
    }
}
