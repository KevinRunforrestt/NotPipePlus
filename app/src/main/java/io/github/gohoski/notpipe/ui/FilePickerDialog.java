package io.github.gohoski.notpipe.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal in-app file browser for Android 1.5+ (2.3 devices have no
 * system file picker): an AlertDialog with a ListView over the SD card.
 * Folders are browsable, candidate import files (.csv / .json / .txt)
 * are selectable, the title shows the current path, and navigation up
 * beyond the SD card (to /, e.g. for a USB stick) stays possible.
 *
 * Java 1.5 (Android 1.5+).
 */
public class FilePickerDialog implements AdapterView.OnItemClickListener {

    /** Called with an absolute path when the user taps a selectable file. */
    public interface Listener {
        void onFilePicked(String path);
    }

    private static final String[] EXTS = { ".csv", ".json", ".txt" };

    private final Activity context;
    private final Listener listener;
    private File current;
    private AlertDialog dialog;
    private Row[] rows;
    private final Drawable folderIcon;
    private final Drawable fileIcon;

    public FilePickerDialog(Activity context, File startDir, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.current = startDir != null && startDir.isDirectory() ? startDir
                : new File("/");
        this.folderIcon = context.getResources().getDrawable(android.R.drawable.ic_menu_view);
        this.fileIcon = context.getResources().getDrawable(android.R.drawable.ic_menu_edit);
    }

    public void show() {
        if (!listCurrent()) return;
        dialog = new AlertDialog.Builder(context)
                .setTitle(ellipsizeStart(current.getPath()))
                .setAdapter(new PickerAdapter(), null)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();
        ListView list = dialog.getListView();
        list.setOnItemClickListener(this);
        dialog.show();
    }

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Row row = rows[position];
        if (row.up) {
            File up = current.getParentFile();
            if (up != null) {
                current = up;
                refresh();
            }
            return;
        }
        if (row.file.isDirectory()) {
            current = row.file;
            refresh();
            return;
        }
        dialog.dismiss();
        if (listener != null) listener.onFilePicked(row.file.getPath());
    }

    private void refresh() {
        if (!listCurrent()) {
            // Unreadable folder: stay where we were if possible.
            return;
        }
        if (dialog != null && dialog.isShowing()) {
            dialog.setTitle(ellipsizeStart(current.getPath()));
            ListView list = dialog.getListView();
            list.setAdapter(new PickerAdapter());
            list.invalidateViews();
        }
    }

    /** Fills rows[]; false when the folder cannot be listed at all. */
    private boolean listCurrent() {
        File[] entries;
        try {
            entries = current.listFiles();
        } catch (Throwable t) {
            entries = null;
        }
        List<Row> result = new ArrayList<Row>();
        if (current.getParentFile() != null) {
            result.add(new Row(null, true));
        }
        if (entries != null) {
            Arrays.sort(entries, new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
                }
            });
            for (int i = 0; i < entries.length; i++) {
                File f = entries[i];
                String name = f.getName();
                if (name.startsWith(".")) continue;
                if (f.isDirectory()) {
                    result.add(new Row(f, false));
                } else if (hasKnownExt(name)) {
                    result.add(new Row(f, false));
                }
            }
        }
        rows = (Row[]) result.toArray(new Row[result.size()]);
        return true;
    }

    private static boolean hasKnownExt(String name) {
        String lower = name.toLowerCase();
        for (int i = 0; i < EXTS.length; i++) {
            if (lower.endsWith(EXTS[i])) return true;
        }
        return false;
    }

    private static String ellipsizeStart(String path) {
        if (path.length() <= 34) return path;
        return "..." + path.substring(path.length() - 31);
    }

    private class PickerAdapter extends BaseAdapter {
        public int getCount() {
            return rows.length;
        }

        public Object getItem(int position) {
            return rows[position];
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view;
            if (convertView instanceof TextView) {
                view = (TextView) convertView;
            } else {
                view = (TextView) LayoutInflater.from(context)
                        .inflate(android.R.layout.simple_list_item_1, parent, false);
                view.setPadding(12, 8, 12, 8);
            }
            Row row = rows[position];
            if (row.up) {
                view.setText("..");
                view.setTypeface(Typeface.DEFAULT_BOLD);
                view.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            } else {
                view.setTypeface(Typeface.DEFAULT);
                if (row.file.isDirectory()) {
                    view.setText(row.file.getName());
                    view.setCompoundDrawablesWithIntrinsicBounds(folderIcon, null, null, null);
                    view.setCompoundDrawablePadding(8);
                } else {
                    view.setText(row.file.getName() + "  (" + sizeLabel(row.file.length()) + ")");
                    view.setCompoundDrawablesWithIntrinsicBounds(fileIcon, null, null, null);
                    view.setCompoundDrawablePadding(8);
                }
            }
            return view;
        }
    }

    private static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return new DecimalFormat("#.#").format(bytes / 1024.0) + " KB";
        }
        return new DecimalFormat("#.#").format(bytes / (1024.0 * 1024.0)) + " MB";
    }

    private static class Row {
        final File file;
        final boolean up;

        Row(File file, boolean up) {
            this.file = file;
            this.up = up;
        }
    }
}
