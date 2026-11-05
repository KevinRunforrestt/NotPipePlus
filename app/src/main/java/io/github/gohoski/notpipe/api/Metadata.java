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

/** Created by Gleb on 09.01.2026. */

public interface Metadata {
    String getName(); String getHost();

    /** Popular videos can be implemented through YouTube hyped playlists via Utils.getHypePlaylist() */
    List<VideoInfo> getPopularVideos() throws IOException;
    List<VideoInfo> search(String q) throws IOException;

    /** Paginated search (NewPipe-style infinite scroll): first page when nextToken is null. */
    SearchPage searchPage(String q, String nextToken) throws IOException;

    List<String> searchSuggestions(String q) throws IOException;

    Video getVideo(String id) throws IOException;
    List<VideoInfo> getRelated(String id) throws IOException;
    List<Comment> getComments(String id) throws IOException;

    /** Paginated comments (NewPipe loads every page as you scroll): the returned page carries the absolute URL. */
    CommentsPage getCommentsPage(String id) throws IOException;

    String getThumbnail(String id);

    /** This method resolves both @usernames and UC channel IDs! */
    Channel getChannel(String id) throws IOException;
    String getChannelIcon(String id) throws IOException;
    /** If the channel videos endpoint doesn't exist or is unreliable, you can fallback to using channel playlist. */
    List<VideoInfo> getChannelVideos(String id, int sort) throws IOException;

    /** Public playlists created by a channel (YouTube "Created playlists"). */
    List<ChannelPlaylist> getChannelPlaylists(String id) throws IOException;

    /** Videos of one playlist (PL... */
    List<VideoInfo> getPlaylistVideos(String playlistId) throws IOException;

    /** Subtitle/caption tracks available for a video (WebVTT behind each url). */
    List<SubtitleTrack> getSubtitles(String videoId) throws IOException;
}
