package io.github.gohoski.notpipe.data;

/**
 * One subtitle/caption track of a video, as exposed by the frontends.
 * The URL points to a WebVTT file the proxies serve natively:
 *  - Invidious: /api/v1/captions/{id}?label=... (WebVTT)
 *  - Piped: the YouTube timedtext URL via the instance proxy (fmt=vtt)
 */
public class SubtitleTrack {
    public final String label;
    public final String code;
    public final String url;

    public SubtitleTrack(String label, String code, String url) {
        this.label = label == null ? "" : label;
        this.code = code == null ? "" : code;
        this.url = url == null ? "" : url;
    }
}
