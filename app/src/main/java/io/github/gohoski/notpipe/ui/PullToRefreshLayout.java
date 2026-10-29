package io.github.gohoski.notpipe.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.TextView;

import io.github.gohoski.notpipe.R;

/**
 * Pull-down-to-refresh container for the subscriptions feed, built the way
 * SwipeRefreshLayout works but on API-3 primitives (no translations, no
 * animation framework): a status header expands between the top edge and
 * the target list while the finger drags, snaps back when released below
 * the threshold and fires the refresh above it.
 *
 * Manual refresh is available in every feed mode (NewPipe refreshes all
 * channels on its manual gesture).
 */
public class PullToRefreshLayout extends FrameLayout {

    /** Listener notified when the pull passes the threshold and releases. */
    public interface OnRefreshListener {
        void onRefresh();
    }

    /** Pull distance (px) after which releasing triggers the refresh. */
    private static final int TRIGGER_DIP = 70;
    /** Fraction of the finger travel applied to the header height. */
    private static final float DAMP = 0.5f;

    private AdapterView target;
    private OnRefreshListener listener;
    private TextView header;
    private boolean refreshing = false;
    private boolean pulling = false;
    private float downY;
    private int triggerPx;
    private int headerHeight = 0;

    public PullToRefreshLayout(Context context) {
        super(context);
        init(context);
    }

    public PullToRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        triggerPx = (int) (TRIGGER_DIP * getResources().getDisplayMetrics().density);
        header = new TextView(context);
        header.setText(R.string.feed_pull);
        header.setTextSize(14);
        header.setGravity(android.view.Gravity.CENTER);
        header.setBackgroundColor(0xAA2B2B2B);
        header.setVisibility(View.GONE);
        // Added LAST so it draws ON TOP of the target list: the indicator
        // grows over the first rows while the finger drags (no translation
        // API on Gingerbread — the list itself stays put).
        addView(header, new LayoutParams(LayoutParams.FILL_PARENT, 0));
    }

    /** The scrollable whose top overscroll drives the gesture. */
    public void setTarget(AdapterView target) {
        this.target = target;
    }

    public void setOnRefreshListener(OnRefreshListener listener) {
        this.listener = listener;
    }

    /** Call when the refresh finished: collapses the header. */
    public void onRefreshComplete() {
        refreshing = false;
        pulling = false;
        setHeaderHeight(0);
    }

    private void setHeaderHeight(int px) {
        headerHeight = px;
        LayoutParams lp = (LayoutParams) header.getLayoutParams();
        if (lp == null) return;
        lp.height = px;
        header.setLayoutParams(lp);
        header.setVisibility(px > 0 ? View.VISIBLE : View.GONE);
        header.setText(refreshing ? R.string.feed_refreshing
                : (px >= triggerPx ? R.string.feed_release : R.string.feed_pull));
    }

    /** True when the target shows its very first row at (or above) the top. */
    private boolean isTargetAtTop() {
        if (target == null) return false;
        if (target.getFirstVisiblePosition() > 0) return false;
        View first = target.getChildAt(0);
        return first == null || first.getTop() >= 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (refreshing || listener == null) return false;
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!pulling && isTargetAtTop()) {
                    float dy = ev.getY() - downY;
                    if (dy > 8) {
                        pulling = true;
                        return true; // own the gesture from now on
                    }
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (refreshing || listener == null) return true;
        switch (ev.getAction()) {
            case MotionEvent.ACTION_MOVE:
                if (isTargetAtTop() || pulling) {
                    float dy = ev.getY() - downY;
                    if (dy > 0) {
                        setHeaderHeight((int) (dy * DAMP));
                        pulling = true;
                        return true;
                    }
                    setHeaderHeight(0);
                    pulling = false;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling) {
                    if (headerHeight >= triggerPx) {
                        refreshing = true;
                        setHeaderHeight(triggerPx);
                        header.setText(R.string.feed_refreshing);
                        if (listener != null) listener.onRefresh();
                    } else {
                        setHeaderHeight(0);
                    }
                    pulling = false;
                }
                break;
        }
        return true;
    }
}
