package io.github.gohoski.notpipe.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

import io.github.gohoski.notpipe.NotPipe;
import io.github.gohoski.notpipe.Utils;

/**
 * Watches connectivity and tells the visible activity when the internet
 * comes back, so a screen left on an error/spinner can reload itself.
 * CONNECTIVITY_ACTION is the only reliable signal on Android 1.5-2.3; the
 * receiver lives in the Application scope, listeners attach while resumed.
 */
public final class ConnectivityMonitor {

    /** Called on the main thread when the network was down and is back. */
    public interface Listener {
        void onConnectionRestored();
    }

    /** UI-thread hop for the callbacks (the receiver can fire off-main). */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Registered activities; copy-on-notify keeps it iteration-safe. */
    private static final List<Listener> listeners = new ArrayList<Listener>();
    /** True while the device has been seen without a usable network. */
    private static volatile boolean offline = false;
    private static boolean receiverRegistered = false;
    private static final Object REGISTER_LOCK = new Object();

    /** Called once from Application.onCreate: arms the system receiver. */
    public static void init(Context appContext) {
        synchronized (REGISTER_LOCK) {
            if (receiverRegistered || appContext == null) return;
            receiverRegistered = true;
            appContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    onConnectivityChanged();
                }
            }, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
        // Seed the baseline so the first "connected" event after an offline
        // start (app opened with airplane mode, WiFi joined later) fires.
        offline = !Utils.hasConnection(appContext);
    }

    /** Cheap stateless check any code can use (UI thread or not). */
    public static boolean isOnline() {
        Context ctx = NotPipe.getAppContext();
        return Utils.hasConnection(ctx);
    }

    /** Attach from onResume; the same listener cannot register twice. */
    public static void register(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
        // The app may have gone offline while nothing was listening.
        offline = offline || !isOnline();
    }

    /** Detach from onPause. */
    public static void unregister(Listener l) {
        if (l == null) return;
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    /** CONNECTIVITY_ACTION handler: fire only on offline -> online. */
    private static void onConnectivityChanged() {
        boolean online = isOnline();
        boolean wasOffline = offline;
        offline = !online;
        if (online && wasOffline) {
            MAIN.post(new Runnable() {
                public void run() {
                    List<Listener> toNotify;
                    synchronized (listeners) {
                        toNotify = new ArrayList<Listener>(listeners);
                    }
                    for (int i = 0; i < toNotify.size(); i++) {
                        try {
                            toNotify.get(i).onConnectionRestored();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }
    }

    private ConnectivityMonitor() {}
}
