package io.github.gohoski.notpipe.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.R;
import io.github.gohoski.notpipe.api.Manager;
import io.github.gohoski.notpipe.config.Config;
import io.github.gohoski.notpipe.config.ConfigManager;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/**
 * Updates the API instance lists from the remote URL. Runs on its own
 * thread (Android 2.3 queues AsyncTasks serially, this job must never
 * hold them), uses the application context for dialogs (an activity-tied
 * one crashed when the activity died) and never wipes a working list
 * with an empty/broken remote payload.
 */
public class InstancesUpdater {

    private final Context context;
    private final Config config;
    private final OnInstancesUpdatedListener listener;

    /** Hops the listener callback to the main thread. */
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public InstancesUpdater(Context context, OnInstancesUpdatedListener listener) {
        this.context = context;
        this.config = ConfigManager.getInstance().getConfig();
        this.listener = listener;
    }

    /**
     * Updates instances from the configured URL in the background.
     */
    public void updateInstances() {
        try {
            Context app = context != null ? context.getApplicationContext() : null;
            if (app != null) {
                Toast.makeText(app, app.getString(R.string.updating_instances),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
        // Plain thread on purpose (see class comment): never occupies the
        // serial Gingerbread AsyncTask queue.
        new Thread(new Runnable() {
            public void run() {
                final boolean success = doUpdate();
                MAIN.post(new Runnable() {
                    public void run() {
                        try {
                            if (listener != null) listener.onInstancesUpdated();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        }, "instances-update").start();
    }

    /**
     * Callback interface for when instances have been updated.
     */
    public interface OnInstancesUpdatedListener {
        void onInstancesUpdated();
    }

    private boolean doUpdate() {
        try {
            JSONObject obj = JSON.getObject(HttpClient.executeToString(
                    new HttpRequest(config.getInstancesUpdateUrl())));

            // Never wipe a working list with an empty one: a bad payload
            // must not leave the app without instances.
            applyList(obj, "ytapilegacy", new Setter() {
                public void set(List<String> l) {
                    config.setYtApiLegacyInstances(l);
                }
            });
            applyList(obj, "invidious", new Setter() {
                public void set(List<String> l) {
                    config.setInvidiousInstances(l);
                }
            });
            applyList(obj, "yt2009", new Setter() {
                public void set(List<String> l) {
                    config.setYt2009Instances(l);
                }
            });
            applyList(obj, "piped", new Setter() {
                public void set(List<String> l) {
                    config.setPipedInstances(l);
                }
            });

            config.setLastUpdate(System.currentTimeMillis());
            ConfigManager.getInstance().saveConfig(config);

            Manager.getInstance().reloadInstances();
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                Context app = context != null ? context.getApplicationContext() : null;
                if (app != null) {
                    final String msg = t.getMessage() == null ? "unknown error" : t.getMessage();
                    Toast.makeText(app, "Instances update failed: " + msg,
                            Toast.LENGTH_LONG).show();
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /** Sets only when the payload list is missing nothing and is non-empty. */
    private static void applyList(JSONObject obj, String key, Setter setter) {
        try {
            JSONArray arr = obj.getArray(key);
            if (arr == null) return;
            List<String> instances = new ArrayList<String>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                String s = arr.getString(i);
                if (s != null && s.trim().length() > 0) instances.add(s.trim());
            }
            if (instances.isEmpty()) return; // keep the previous, working list
            setter.set(instances);
        } catch (Throwable ignored) {
            // Missing/broken key: keep the previous list for this backend.
        }
    }

    private interface Setter {
        void set(List<String> instances);
    }
}
