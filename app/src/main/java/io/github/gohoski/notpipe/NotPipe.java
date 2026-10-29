package io.github.gohoski.notpipe;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.config.ConfigManager;

/**
 * Application entry point: stores the app context and initializes the
 * singletons in the right order (ConfigManager feeds the Manager).
 */
public class NotPipe extends Application {
    public static final int SDK = Integer.parseInt(Build.VERSION.SDK);
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;
        ConfigManager.init(this);
        ConfigManager.getInstance().ensureInstancesConfigured();
        Manager.init();
        SSLDisabler.disableSSLCertificateChecking();
    }

    public static Context getAppContext() {
        return appContext;
    }
}
