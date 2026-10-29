package io.github.gohoski.notpipe.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Adapter;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * A simple LinearLayout that supports adapters like ListView.
 * Unlike ListView, this expands to fit all items and doesn't scroll internally,
 * making it suitable for use inside ScrollView on Android 1.6+.
 * 
 * Note: Creates ALL child views immediately. Adapters should handle lazy loading themselves.
 */
public class AdapterLinearLayout extends LinearLayout {
    private Adapter adapter;
    private OnItemClickListener onItemClickListener;
    private DataSetObserver dataSetObserver;
    private List<View> childViews = new ArrayList<View>();
    private int lastCount = 0;

    public AdapterLinearLayout(Context context) {
        super(context);
        init();
    }

    public AdapterLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        dataSetObserver = new DataSetObserver();
    }

    /**
     * Sets the adapter for this view.
     */
    public void setAdapter(Adapter adapter) {
        if (this.adapter != null) {
            this.adapter.unregisterDataSetObserver(dataSetObserver);
        }
        this.adapter = adapter;
        if (this.adapter != null) {
            this.adapter.registerDataSetObserver(dataSetObserver);
        }
        refreshViews();
    }

    /**
     * Sets a listener for item clicks.
     */
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.onItemClickListener = listener;
    }

    /**
     * Refreshes all child views based on adapter data.
     */
    private void refreshViews() {
        childViews.clear();
        removeAllViews();
        lastCount = 0;
        if (adapter == null) {
            return;
        }

        for (int i = 0; i < adapter.getCount(); i++) {
            View view = adapter.getView(i, null, this);
            childViews.add(view);
            addView(view);

            final int position = i;
            view.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (onItemClickListener != null) {
                        onItemClickListener.onItemClick(
                            AdapterLinearLayout.this,
                            v,
                            position,
                            adapter.getItemId(position)
                        );
                    }
                }
            });
        }
        lastCount = adapter.getCount();
        requestLayout();
        invalidate();
    }
    
    /**
     * Updates specific child views without recreating the entire list.
     * Call this when only certain items need to be refreshed.
     */
    public void updateViews() {
        if (adapter == null) return;
        for (int i = 0; i < childViews.size() && i < adapter.getCount(); i++) {
            View oldView = childViews.get(i);
            // Always call getView - it will update the view in place via ViewHolder pattern
            adapter.getView(i, oldView, this);
        }
        invalidate();
    }

    /**
     * Interface for item click callbacks.
     */
    public interface OnItemClickListener {
        void onItemClick(AdapterLinearLayout parent, View view, int position, long id);
    }

    /**
     * DataSetObserver implementation to watch for adapter changes.
     */
    private class DataSetObserver extends android.database.DataSetObserver {
        @Override
        public void onChanged() {
            int currentCount = (adapter != null) ? adapter.getCount() : 0;

            if (currentCount != lastCount) {
                // Item count changed, need to recreate views
                refreshViews();
                lastCount = currentCount;
            } else {
                // Same count, just update existing views
                updateViews();
            }
        }

        @Override
        public void onInvalidated() {
            lastCount = 0;
            refreshViews();
        }
    }
}
