package io.github.gohoski.notpipe.data;

import java.util.ArrayList;
import java.util.List;

/** One page of search results plus the token of the next page (null when there are no more). */
public class SearchPage {
    public final List<VideoInfo> videos;
    /** Opaque token of the next page, or null after the last one. */
    public final String nextToken;

    public SearchPage(List<VideoInfo> videos, String nextToken) {
        this.videos = videos != null ? videos : new ArrayList<VideoInfo>();
        this.nextToken = nextToken;
    }
}
