package io.github.gohoski.notpipe.data;

/**
 * One public playlist of a channel ("Created playlists" on YouTube),
 * as listed by Metadata.getChannelPlaylists.
 */
public class ChannelPlaylist {
    public final String id;
    public final String title;
    public final String thumbnail;
    /** -1 = unknown. */
    public final int videoCount;

    public ChannelPlaylist(String id, String title, String thumbnail, int videoCount) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.thumbnail = thumbnail == null ? "" : thumbnail;
        this.videoCount = videoCount;
    }
}
