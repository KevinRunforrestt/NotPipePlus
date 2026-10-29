package io.github.gohoski.notpipe.ui;

import android.app.ProgressDialog;
import android.content.Context;

import io.github.gohoski.notpipe.R;

/**
 * Created by Gleb on 24.10.2025.
 * Loading popup
 */

public class Loading extends ProgressDialog {
    public Loading(Context context, int message) {
        super(context);
        this.setMessage(context.getString(message));
        this.setCancelable(false);
        this.show();
    }

    Loading(Context context) {
        this(context, R.string.loading);
    }
}
