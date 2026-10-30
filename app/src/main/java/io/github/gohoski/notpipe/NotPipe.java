package io.github.gohoski.notpipe;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.util.ConnectivityMonitor;
import io.github.gohoski.notpipe.util.CrashLogger;

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
        // Crash traces to a file FIRST: anything that fails from here on
        // stays diagnosable without adb (logcat's FATAL block carries no
        // package name, so the usual "grep <package>" never shows it).
        CrashLogger.install();
        appContext = this;
        ConfigManager.init(this);
        ConfigManager.getInstance().ensureInstancesConfigured();
        Manager.init();
        SSLDisabler.disableSSLCertificateChecking();
        // Offline->online watcher: lets the visible screen reload itself
        // once the network is back instead of staying on an error.
        ConnectivityMonitor.init(this);
    }

    public static Context getAppContext() {
        return appContext;
    }
}
