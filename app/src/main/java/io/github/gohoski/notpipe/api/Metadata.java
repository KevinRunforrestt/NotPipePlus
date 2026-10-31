package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.util.List;

import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.ChannelPlaylist;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.SearchPage;
import io.github.gohoski.notpipe.data.SubtitleTrack;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;

/**
 * Created by Gleb on 09.01.2026.
 */

public interface Metadata {
    String getName(); String getHost();

    /**
     * Popular videos can be implemented through YouTube hyped playlists via Utils.getHypePlaylist()
     */
    List<VideoInfo> getPopularVideos() throws IOException;
    List<VideoInfo> search(String q) throws IOException;

    /**
     * Paginated search (NewPipe-style infinite scroll): first page when
     * nextToken is null, otherwise the page the token points to.
     * Implementations that cannot paginate should throw an IOException so
     * the Manager falls back to another frontend.
     */
    SearchPage searchPage(String q, String nextToken) throws IOException;

    List<String> searchSuggestions(String q) throws IOException;

    Video getVideo(String id) throws IOException;
    List<VideoInfo> getRelated(String id) throws IOException;
    List<Comment> getComments(String id) throws IOException;

    /**
     * Paginated comments (NewPipe loads every page as you scroll): the
     * returned page carries the absolute URL of the next one. Implementations
     * that cannot paginate should throw an IOException so the Manager falls
     * back to another frontend.
     */
    CommentsPage getCommentsPage(String id) throws IOException;

    String getThumbnail(String id);

    /** This method resolves both @usernames and UC channel IDs! */
    Channel getChannel(String id) throws IOException;
    String getChannelIcon(String id) throws IOException;
    /**
     * If the channel videos endpoint doesn't exist or is unreliable, you can fallback to using channel playlist
     * (`"UU" + id.substring(2)`)
     * If Metadata.getChannelVideos fails to retrieve videos, please use ChannelApi
     * @param id Channel ID
     * @param sort 0 = Latest, 1 = Popular
     */
    List<VideoInfo> getChannelVideos(String id, int sort) throws IOException;

    /**
     * Public playlists created by a channel (YouTube "Created playlists").
     * An empty list means the channel has no public playlists.
     * @param id Channel ID (UC...)
     */
    List<ChannelPlaylist> getChannelPlaylists(String id) throws IOException;

    /**
     * Videos of one playlist (PL... id). Order matches the playlist order.
     */
    List<VideoInfo> getPlaylistVideos(String playlistId) throws IOException;

    /**
     * Subtitle/caption tracks available for a video (WebVTT behind each url).
     * An empty list means the video has no captions.
     * Implementations that cannot provide subtitles should throw an
     * IOException so the Manager can fall back to another frontend.
     */
    List<SubtitleTrack> getSubtitles(String videoId) throws IOException;
}
