package io.github.gohoski.notpipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.util.List;

import io.github.gohoski.notpipe.ui.FilePickerDialog;
import io.github.gohoski.notpipe.util.ChannelIconResolver;
import io.github.gohoski.notpipe.util.ImageLoader;
import io.github.gohoski.notpipe.util.SubscriptionsImporter;

/**
 * Subscriptions manager: the list of subscribed channels with avatars.
 * Tap opens the channel; long-press unsubscribes (with confirmation);
 * the menu imports a Google Takeout / NewPipe export and exports the
 * current list back as CSV on the SD card.
 */
public class SubscriptionsActivity extends Activity {

    private ListView list;
    private TextView empty;
    private List<String[]> subs;
    private SubsAdapter adapter;
    private boolean isDestroyedFlag = false;

    /** onActivityResult request code for the system file picker. */
    private static final int REQ_PICK_SUBS_FILE = 7001;
    /** Never load an insane "csv" into a 2011 heap. */
    private static final int MAX_IMPORT_BYTES = 8 * 1024 * 1024;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_subscriptions);

        list = (ListView) findViewById(R.id.subs_list);
        empty = (TextView) findViewById(R.id.empty_subs_list);

        subs = Subscriptions.get();
        adapter = new SubsAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String[] sub = subs.get(position);
                Intent intent = new Intent(SubscriptionsActivity.this, ChannelActivity.class);
                intent.putExtra("ID", sub[0]);
                startActivity(intent);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                final String[] sub = subs.get(position);
                new AlertDialog.Builder(SubscriptionsActivity.this)
                        .setTitle(R.string.unsubscribe_q)
                        .setMessage(FeedLogic.channelName(sub))
                        .setPositiveButton(R.string.unsubscribe, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                Subscriptions.unsubscribe(sub[0]);
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
        subs = Subscriptions.get();
        if (subs.isEmpty()) {
            empty.setText(R.string.no_subs_channels);
        } else {
            empty.setText(R.string.subs_hint);
        }
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_subscriptions, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_import_subs) {
            startImport();
            return true;
        } else if (item.getItemId() == R.id.action_export_subs) {
            exportSubscriptions();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------
    // Import: native picker first, integrated browser as fallback
    // ------------------------------------------------------------------

    /**
     * Opens a file picker for the import. Android 2.3 has no system-wide
     * file browser: if some installed app can serve ACTION_GET_CONTENT the
     * chooser opens it, otherwise the integrated FilePickerDialog browses
     * the SD card — any file, any folder (the old hardcoded
     * /sdcard/notPipe/subscriptions.csv path reported "0 channels" for
     * everything stored elsewhere).
     */
    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                startActivityForResult(intent, REQ_PICK_SUBS_FILE);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Chooser could not start after all: fall through.
            }
        }
        showIntegratedPicker();
    }

    /** Integrated fallback: browse the SD card inside the app. */
    private void showIntegratedPicker() {
        File start = android.os.Environment.getExternalStorageDirectory();
        if (start == null || !start.isDirectory()) start = new File("/");
        new FilePickerDialog(this, start, new FilePickerDialog.Listener() {
            public void onFilePicked(String path) {
                importFromFile(path);
            }
        }).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_SUBS_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return; // cancelled
        }
        Uri uri = data.getData();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if ("file".equals(scheme)) {
            importFromFile(uri.getPath());
        } else {
            importFromUri(uri);
        }
    }

    /** Normalizes both paths into a background read + parse + merge. */
    private void importFromFile(String path) {
        if (path == null || path.length() == 0) {
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
            return;
        }
        new ImportTask().execute(path);
    }

    private void importFromUri(Uri uri) {
        new ImportTask().execute(uri.toString());
    }

    /** Reads the picked file, parses (universal formats) and merges,
     *  off the UI thread. */
    private class ImportTask extends AsyncTask<String, Void, int[]> {
        private String failReason;

        @Override
        protected int[] doInBackground(String... params) {
            InputStream in = null;
            byte[] data = null;
            try {
                String target = params[0];
                if (target.startsWith("content://")) {
                    ContentResolver resolver = getContentResolver();
                    in = resolver.openInputStream(Uri.parse(target));
                    if (in == null) throw new FileNotFoundException("no stream");
                } else {
                    in = new FileInputStream(target);
                }
                data = readFully(in, MAX_IMPORT_BYTES);
            } catch (Throwable t) {
                failReason = t.getClass().getSimpleName();
                return null;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
            List<String[]> parsed = SubscriptionsImporter.parse(data);
            int total = parsed.size();
            int added = Subscriptions.addAll(parsed);
            return new int[]{ added, total - added };
        }

        @Override
        protected void onPostExecute(int[] result) {
            if (isDestroyedFlag || isFinishing()) return;
            if (result == null) {
                Toast.makeText(SubscriptionsActivity.this,
                        R.string.import_error, Toast.LENGTH_LONG).show();
                return;
            }
            int added = result[0];
            int skipped = result[1];
            if (added == 0 && skipped == 0) {
                new AlertDialog.Builder(SubscriptionsActivity.this)
                        .setTitle(R.string.import_subscriptions)
                        .setMessage(R.string.import_no_channels)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            } else {
                Toast.makeText(SubscriptionsActivity.this,
                        getString(R.string.imported_subs_detail, new Object[]{
                                Integer.valueOf(added), Integer.valueOf(skipped) }),
                        Toast.LENGTH_LONG).show();
            }
            refresh();
        }
    }

    /** Reads a stream fully, capped: a 2011 device must not OOM on a
     *  mis-picked 500 MB "file". */
    private static byte[] readFully(InputStream in, int cap) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(16384);
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > cap) throw new Exception("file too large");
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /** Writes the current list to /sdcard/notPipe/subscriptions.csv
     *  (Google Takeout shape, so it re-imports here and in NewPipe). */
    private void exportSubscriptions() {
        try {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "notPipe");
            dir.mkdirs();
            File file = new File(dir, "subscriptions.csv");
            PrintWriter writer = new PrintWriter(new FileWriter(file));
            writer.println("Channel ID,Channel URL,Channel Title");
            List<String[]> all = Subscriptions.get();
            for (int i = 0; i < all.size(); i++) {
                String[] s = all.get(i);
                writer.println(s[0] + ",https://www.youtube.com/channel/" + s[0]
                        + "," + FeedLogic.channelName(s).replace(',', ' '));
            }
            writer.close();
            Toast.makeText(this, getString(R.string.exported_subscriptions, file.getPath()),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyedFlag = true;
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.clearCache();
    }

    private class SubsAdapter extends BaseAdapter {
        private LayoutInflater inflater;
        private final java.util.HashSet<String> healedChannels = new java.util.HashSet<String>();

        SubsAdapter() {
            inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        }

        public int getCount() {
            return subs.size();
        }

        public Object getItem(int position) {
            return subs.get(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_subscription, parent, false);
            }
            String[] sub = subs.get(position);
            ((TextView) convertView.findViewById(R.id.channel_title)).setText(FeedLogic.channelName(sub));
            final String channelId = sub[0];
            ImageView thumb = (ImageView) convertView.findViewById(R.id.channel_thumbnail);
            String avatar = sub.length > 2 ? sub[2] : null;
            if (avatar != null && avatar.length() > 0 && !avatar.equals(thumb.getTag())) {
                thumb.setTag(avatar);
                thumb.setImageResource(android.R.drawable.ic_menu_myplaces);
                ImageLoader.loadImage(avatar, thumb, true, new ImageLoader.ImageLoaderCallback() {
                    public void onFail() {
                        healAvatar(channelId);
                    }
                });
            } else if (avatar == null || avatar.length() == 0) {
                if (thumb.getTag() != null) thumb.setTag(null);
                thumb.setImageResource(android.R.drawable.ic_menu_myplaces);
            }
            return convertView;
        }

        /** The stored avatar URL rotted (its instance died): re-resolve
         *  the icon, persist the fresh URL and rebind. One attempt per
         *  channel per session. */
        private void healAvatar(final String channelId) {
            if (channelId == null || channelId.length() == 0) return;
            if (healedChannels.contains(channelId)) return;
            healedChannels.add(channelId);
            String resolved = ChannelIconResolver.getResolved(channelId);
            if (ChannelIconResolver.FAILED.equals(resolved)) return; // already known dead this session
            if (resolved != null && resolved.length() > 0) {
                adoptAvatar(channelId, resolved);
                return;
            }
            ChannelIconResolver.requestFallback(channelId,
                    new ChannelIconResolver.OnIconResolvedListener() {
                public void onIconResolved(String cid, String url) {
                    if (url != null && url.length() > 0) adoptAvatar(cid, url);
                }
            });
        }

        /** Persists the working URL and rebinds the rows. */
        private void adoptAvatar(final String channelId, String url) {
            Subscriptions.setAvatar(channelId, url);
            runOnUiThread(new Runnable() {
                public void run() {
                    if (isDestroyedFlag || isFinishing()) return;
                    refresh(); // re-reads the persisted avatar
                }
            });
        }
    }
}
