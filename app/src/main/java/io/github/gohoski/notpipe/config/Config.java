package io.github.gohoski.notpipe.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by opencode on 15.02.2026.
 * Data class holding user configuration settings.
 * Uses Java 1.5 compatible getters/setters (no Lombok/data classes).
 */
public class Config {

    // Subscriptions feed refresh modes (NewPipe-style, configurable).
    public static final int FEED_REFRESH_ON_OPEN = 0;
    public static final int FEED_REFRESH_MANUAL = 1;
    public static final int FEED_REFRESH_INTERVAL = 2;

    private List<String> invidiousInstances;
    private List<String> yt2009Instances;
    private List<String> pipedInstances;
    private List<String> ytApiLegacyInstances;
    private String preferredQuality;
    private boolean updateInstancesFromUrl;
    private String instancesUpdateUrl;
    private int updateFrequency;
    private boolean useExternalPlayer;
    private boolean streamPlayback;
    private long lastUpdate;
    private boolean convertVideos;
    private int convertCodec;
    private boolean asyncSetVideoUri;
    private boolean fullscreenRotateLandscape;
    private int feedRefreshMode;
    private int feedRefreshIntervalMinutes;
    private boolean hidePopularFeed;

    Config() {
        invidiousInstances = new ArrayList<String>();
        yt2009Instances = new ArrayList<String>();
        pipedInstances = new ArrayList<String>();
        ytApiLegacyInstances = new ArrayList<String>();
        fullscreenRotateLandscape = true;
        // Feed refresh defaults: current behavior (load new videos on app
        // open) and a 1 hour auto interval when the interval mode is used.
        feedRefreshMode = FEED_REFRESH_ON_OPEN;
        feedRefreshIntervalMinutes = 60;
    }

    /**
     * Copy constructor for creating a defensive copy.
     */
    public Config(Config other) {
        invidiousInstances = new ArrayList<String>(other.invidiousInstances);
        yt2009Instances = new ArrayList<String>(other.yt2009Instances);
        pipedInstances = new ArrayList<String>(other.pipedInstances);
        ytApiLegacyInstances = new ArrayList<String>(other.ytApiLegacyInstances);
        preferredQuality = other.preferredQuality;
        updateInstancesFromUrl = other.updateInstancesFromUrl;
        instancesUpdateUrl = other.instancesUpdateUrl;
        updateFrequency = other.updateFrequency;
        useExternalPlayer = other.useExternalPlayer;
        streamPlayback = other.streamPlayback;
        lastUpdate = other.lastUpdate;
        convertVideos = other.convertVideos;
        convertCodec = other.convertCodec;
        asyncSetVideoUri = other.asyncSetVideoUri;
        fullscreenRotateLandscape = other.fullscreenRotateLandscape;
        feedRefreshMode = other.feedRefreshMode;
        feedRefreshIntervalMinutes = other.feedRefreshIntervalMinutes;
        hidePopularFeed = other.hidePopularFeed;
    }

    public List<String> getInvidiousInstances() {
        return invidiousInstances;
    }

    public void setInvidiousInstances(List<String> instances) {
        this.invidiousInstances = instances;
    }

    public List<String> getYt2009Instances() {
        return yt2009Instances;
    }

    public void setYt2009Instances(List<String> instances) {
        this.yt2009Instances = instances;
    }

    public List<String> getPipedInstances() {
        return pipedInstances;
    }

    public void setPipedInstances(List<String> instances) {
        this.pipedInstances = instances;
    }

    public List<String> getYtApiLegacyInstances() {
        return ytApiLegacyInstances;
    }

    public void setYtApiLegacyInstances(List<String> instances) {
        this.ytApiLegacyInstances = instances;
    }

    public String getPreferredQuality() {
        return preferredQuality;
    }

    public void setPreferredQuality(String quality) {
        this.preferredQuality = quality;
    }

    public boolean isUpdateInstancesFromUrl() {
        return updateInstancesFromUrl;
    }

    public void setUpdateInstancesFromUrl(boolean update) {
        this.updateInstancesFromUrl = update;
    }

    public String getInstancesUpdateUrl() {
        return instancesUpdateUrl;
    }

    public void setInstancesUpdateUrl(String url) {
        this.instancesUpdateUrl = url;
    }

    public int getUpdateFrequency() {
        return updateFrequency;
    }

    public void setUpdateFrequency(int frequency) {
        this.updateFrequency = frequency;
    }

    public boolean isUseExternalPlayer() {
        return useExternalPlayer;
    }

    public void setUseExternalPlayer(boolean external) {
        this.useExternalPlayer = external;
    }

    public boolean isStreamPlayback() {
        return streamPlayback;
    }

    public void setStreamPlayback(boolean stream) {
        this.streamPlayback = stream;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(long lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public boolean isConvertVideos() {
        return convertVideos;
    }

    public void setConvertVideos(boolean convertVideos) {
        this.convertVideos = convertVideos;
    }

    public int getConvertCodec() {
        return convertCodec;
    }

    public void setConvertCodec(int convertCodec) {
        this.convertCodec = convertCodec;
    }

    public boolean isAsyncSetVideoUri() {
        return asyncSetVideoUri;
    }

    public void setAsyncSetVideoUri(boolean asyncSetVideoUri) {
        this.asyncSetVideoUri = asyncSetVideoUri;
    }

    public boolean isFullscreenRotateLandscape() {
        return fullscreenRotateLandscape;
    }

    public void setFullscreenRotateLandscape(boolean rotate) {
        this.fullscreenRotateLandscape = rotate;
    }

    public int getFeedRefreshMode() {
        return feedRefreshMode;
    }

    public void setFeedRefreshMode(int mode) {
        this.feedRefreshMode = mode;
    }

    public int getFeedRefreshIntervalMinutes() {
        return feedRefreshIntervalMinutes;
    }

    public void setFeedRefreshIntervalMinutes(int minutes) {
        this.feedRefreshIntervalMinutes = minutes;
    }

    /** True when the user chose to hide the Popular feed completely and
     *  keep only the Subscriptions feed (NewPipe has no forced "trending"
     *  screen either). */
    public boolean isHidePopularFeed() {
        return hidePopularFeed;
    }

    public void setHidePopularFeed(boolean hidePopularFeed) {
        this.hidePopularFeed = hidePopularFeed;
    }

    /**
     * Add default instance. Called by ConfigManager.ensureInstancesConfigured()
     * when all instance lists are empty.
     */
    public void applyDefaults() {
        // Live instances, probed 2026-10-30 against the app's own endpoints
        // (the old single default was a dead server). The daily refresh
        // (updateInstancesFromUrl below) keeps the list current; ytapilegacy
        // has no live instances left, so it stays empty.
        invidiousInstances.add("http://14.224.215.173:3000");
        invidiousInstances.add("http://61.221.6.111");
        invidiousInstances.add("http://160.236.192.102:3000");
        invidiousInstances.add("http://171.236.180.251:3000");
        invidiousInstances.add("http://14.225.212.141:3000");
        invidiousInstances.add("http://nate-stockhaus.amishcrabs.pics");
        invidiousInstances.add("http://73.26.148.72:3000");
        invidiousInstances.add("http://74.102.17.26:8203");
        invidiousInstances.add("http://inv.stappsworld.com");
        invidiousInstances.add("http://157.254.18.46:14120");
        invidiousInstances.add("http://invidious.keltechnas.com");
        invidiousInstances.add("http://inv.tekforge.de");

        yt2009Instances.add("http://87.106.23.41");
        yt2009Instances.add("http://femboy.email:730");

        pipedInstances.add("http://pipedapi.xorrito.info,http://pipedproxy.xorrito.info");
        pipedInstances.add("http://167.114.169.34:8081,http://167.114.169.34:8082");
        pipedInstances.add("http://152.53.31.104:8091/api,http://pp.dfjko.cc:8080");
        pipedInstances.add("http://152.53.170.32:8091/api,http://45.14.186.180:8082");
        pipedInstances.add("http://piped-api.freelancingpeter.eu:8081,http://piped-proxy.freelancingpeter.eu:8082");
        pipedInstances.add("http://pipedapi.mebitek.com,http://pipedproxy.mebitek.com");
        pipedInstances.add("http://pipedapi.hilles.space,http://124.158.10.214:8383");
        pipedInstances.add("http://82.24.19.217:8084,http://82.24.19.217:8085");
        pipedInstances.add("http://pipedapi.stellar.afs.ovh,http://pipedproxy.stellar.afs.ovh");

        updateInstancesFromUrl = true;
        instancesUpdateUrl = "http://144.31.189.129/notPipe.json";
        updateFrequency = 1;
        streamPlayback = true;
        fullscreenRotateLandscape = true;
        feedRefreshMode = FEED_REFRESH_ON_OPEN;
        feedRefreshIntervalMinutes = 60;
        hidePopularFeed = false;
    }
}
