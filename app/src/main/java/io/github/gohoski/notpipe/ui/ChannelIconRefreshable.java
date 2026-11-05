package io.github.gohoski.notpipe.ui;

import android.view.View;

/** Implemented by adapters whose rows display channel icons refreshable after a background resolution. */
public interface ChannelIconRefreshable {

    /** Re-checks and reloads the channel icon for the given row if it belongs to the specified channel. */
    void updateChannelIconForView(View row, String channelId);
}
