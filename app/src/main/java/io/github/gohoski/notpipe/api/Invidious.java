package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONException;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.ChannelPlaylist;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.SearchPage;
import io.github.gohoski.notpipe.data.SubtitleTrack;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;
import io.github.gohoski.notpipe.Utils;

/**
 * Created by Gleb on 19.01.2026.
 * Implementation of Invidious API (https://docs.invidious.io/api/)
 */

class Invidious implements Metadata, VideoStream {
    private String baseUrl;
    private static final int VIDEO_THUMB = 4;
    private static final int AUTHOR_THUMB = 2;

    Invidious(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getName() { return "Invidious"; }
    public String getHost() {
        return baseUrl.replace("https://", "").replace("http://", "");
    }

    /** Video thumbnail of an Invidious row, routed through the instance. */
    private String videoThumbUrl(JSONObject j) {
        return Utils.parseUrl(baseUrl, j.getArray("videoThumbnails").getObject(VIDEO_THUMB).getString("url"));
    }

    /** Author avatar of an Invidious row, routed through /ggpht. */
    private String authorThumbUrl(JSONObject j) {
        return Utils.parseUrl(baseUrl + "/ggpht", j.getArray("authorThumbnails").getObject(AUTHOR_THUMB).getString("url"));
    }

    /**
     * Popular feed with layered endpoints: 1. the classic hype playlists;
     * 2. /api/v1/trending?region=US; 3. /api/v1/popular (companion-backed
     * instances). An empty page throws so the instance fallback chain can
     * try the next instance; one malformed item never kills the response.
     */
    @Override
    public List<VideoInfo> getPopularVideos() throws IOException {
        List<VideoInfo> videos = new ArrayList<VideoInfo>();

        // 1. Hype playlist.
        try {
            HttpRequest req = new HttpRequest(baseUrl, "/api/v1/playlists/" + Utils.getHypePlaylist());
            JSONObject pl = JSON.getObject(HttpClient.executeToString(req));
            JSONArray arr = pl.getArray("videos");
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JSONObject j = arr.getObject(i);
                    if ("video".equals(j.getString("type")))
                        videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                                Utils.parseUrl(baseUrl, thumbUrl(j)),
                                j.getString("author"), "", j.getString("authorId"),
                                Utils.formatDuration(j.getInt("lengthSeconds")), -1, null));
                } catch (JSONException ignored) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!videos.isEmpty()) return videos;

        // 2. Trending (region US; healthy on every instance probed 2026-10).
        try {
            HttpRequest req = new HttpRequest.Builder(baseUrl, "/api/v1/trending")
                    .addParam("region", "US").build();
            JSONArray arr = JSON.getArray(HttpClient.executeToString(req));
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JSONObject j = arr.getObject(i);
                    if ("video".equals(j.getString("type")))
                        videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                                Utils.parseUrl(baseUrl, thumbUrl(j)),
                                j.getString("author"), "", j.getString("authorId"),
                                Utils.formatDuration(j.getInt("lengthSeconds")),
                                j.getLong("viewCount"), null));
                } catch (JSONException ignored) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!videos.isEmpty()) return videos;

        // 3. Invidious /api/v1/popular (works on companion-backed instances).
        try {
            HttpRequest req = new HttpRequest(baseUrl, "/api/v1/popular");
            JSONArray arr = JSON.getArray(HttpClient.executeToString(req));
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JSONObject j = arr.getObject(i);
                    videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                            Utils.parseUrl(baseUrl, thumbUrl(j)),
                            j.getString("author"), "", j.getString("authorId"),
                            Utils.formatDuration(j.getInt("lengthSeconds")), -1, null));
                } catch (JSONException ignored) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (videos.isEmpty()) {
            // An IOException makes the chain try the next instance
            // ("content unavailable" would stop it dead).
            throw new IOException("Popular feed unavailable on " + getHost());
        }
        return videos;
    }

    /** Safest available thumbnail of an Invidious video row. */
    private static String thumbUrl(JSONObject j) {
        JSONArray thumbs = j.getArray("videoThumbnails");
        if (thumbs != null && thumbs.size() > 0) {
            int pick = Math.min(VIDEO_THUMB, thumbs.size() - 1);
            return thumbs.getObject(pick).getString("url");
        }
        return "";
    }

    @Override public List<VideoInfo> search(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/api/v1/search").addParam("q",q).addParam("type", "video").build();
        JSONArray json = JSON.getArray(HttpClient.executeToString(req));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < json.size(); i++) {
            JSONObject j = json.getObject(i);
            if ("video".equals(j.getString("type"))) // the API sometimes returns a channel row
                videos.add(new VideoInfo(
                        j.getString("videoId"), j.getString("title"),
                        videoThumbUrl(j),
                        j.getString("author"),
                        authorThumbUrl(j),
                        j.getString("authorId"), Utils.formatDuration(j.getInt("lengthSeconds")),
                        j.getLong("viewCount"), new Date(j.getLong("published") * 1000L)
                ));
        } return videos;
    }

    /** Paginated search: Invidious uses a plain page number (2, 3, ...). */
    @Override public SearchPage searchPage(String q, String nextToken) throws IOException {
        int page = 1;
        if (nextToken != null && nextToken.length() > 0) {
            try { page = Integer.parseInt(nextToken); } catch (NumberFormatException e) { page = 1; }
        }
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/api/v1/search")
                .addParam("q", q).addParam("type", "video").addParam("page", String.valueOf(page)).build();
        JSONArray json = JSON.getArray(HttpClient.executeToString(req));
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < json.size(); i++) {
            try {
                JSONObject j = json.getObject(i);
                if (!"video".equals(j.getString("type"))) continue;
                videos.add(new VideoInfo(
                        j.getString("videoId"), j.getString("title"),
                        videoThumbUrl(j),
                        j.getString("author"),
                        authorThumbUrl(j),
                        j.getString("authorId"), Utils.formatDuration(j.getInt("lengthSeconds")),
                        j.getLong("viewCount"), new Date(j.getLong("published") * 1000L)
                ));
            } catch (JSONException ignored) {
                // Skip a malformed row, keep the rest.
            }
        }
        if (videos.isEmpty() && page > 1) return new SearchPage(videos, null); // exhausted
        return new SearchPage(videos, String.valueOf(page + 1));
    }

    @Override public List<String> searchSuggestions(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/api/v1/search/suggestions").addParam("q",q).build();
        JSONArray json = JSON.getObject(HttpClient.executeToString(req)).getArray("suggestions");
        List<String> s = new ArrayList<String>();
        for (int i = 0; i < json.size(); i++) {
            s.add(json.getString(i));
        } return s;
    }

    @Override public Video getVideo(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/videos/"+id+"?local=true");
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        List<VideoInfo> related = new ArrayList<VideoInfo>();
        try {
            String error = json.getString("error");
            if (!(error.contains("bot") || error.contains("protect") || error.contains("page") || error.contains("Companion")))
                throw new ContentUnavailableException(error);
        } catch (JSONException ignored) {}
        JSONArray arr = json.getArray("recommendedVideos");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            Date published; try {
                published = sdf.parse(j.getString("published"));
            } catch(Exception e) {
                e.printStackTrace(); published = new Date();
            }
            related.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                    videoThumbUrl(j),
                    j.getString("author"), "", j.getString("authorId"), Utils.formatDuration(j.getInt("lengthSeconds")),
                    Utils.parseTextCount(j.getString("viewCountText")), published));
        }
        return new Video(id, json.getString("title"),
                videoThumbUrl(json), json.getString("author"),
                authorThumbUrl(json),
                json.getString("authorId"), json.getInt("lengthSeconds"), json.getLong("viewCount"),
                new Date(json.getLong("published") * 1000L), json.getString("description"), json.getInt("likeCount"),
                (int)Utils.parseTextCount(json.getString("subCountText")),
                Utils.parseUrl(baseUrl, json.getArray("formatStreams").getObject(0).getString("url")), related, null);
    }

    @Override public String getVideoUrl(String id, String ignored, int ignored2) throws IOException {
        //Invidious can provide a combined stream only in 360p, so we ignore the quality variable
        //Invidious will be disabled for non-360p via the Manager
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/videos/"+id+"?local=true");
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        return Utils.parseUrl(baseUrl, json.getArray("formatStreams").getObject(0).getString("url"));
    }

    @Override public List<Comment> getComments(String id) throws IOException {
        return getCommentsPage(id).comments;
    }

    /**
     * Paginated comments: the first page carries the top-level
     * continuation token of the next one (Invidious schema). Pinned
     * comments ("isPinned" on modern builds, "pinned" on older ones)
     * arrive first and are badged like NewPipe does.
     */
    @Override public CommentsPage getCommentsPage(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/comments/"+id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        // Propagate structured errors instead of swallowing them: several
        // Invidious instances fail to extract comments for specific videos
        // while other frontends still can.
        try {
            String error = json.getString("error");
            if (error != null && error.length() > 0) {
                // "Comments not found." (videos without/disabled comments)
                // is definitive: no instance has them.
                String lower = error.toLowerCase();
                if (lower.contains("not found") || lower.contains("disabled")
                        || lower.contains("unavailable")) {
                    throw new ContentUnavailableException(error);
                }
                throw new IOException("Comments error: " + error);
            }
        } catch (JSONException ignored) {}
        List<Comment> comments = parseCommentRows(json.getArray("comments"), id);
        // A positive advertised count with zero extracted rows means this
        // instance was bot-challenged: throw so the Manager tries another.
        long commentCount = -1;
        try {
            commentCount = json.getLong("commentCount");
        } catch (JSONException ignored) {}
        if (comments.isEmpty() && commentCount > 0) {
            throw new IOException("Comments extraction failed on this instance");
        }
        String moreUrl = null;
        String continuation = json.getNullableString("continuation");
        if (continuation != null && continuation.length() > 0 && !comments.isEmpty()) {
            try {
                moreUrl = baseUrl + "/api/v1/comments/" + id
                        + "?continuation=" + URLEncoder.encode(continuation, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ignored) {
                moreUrl = baseUrl + "/api/v1/comments/" + id + "?continuation=" + continuation;
            }
        }
        return new CommentsPage(comments, moreUrl, Comment.BACKEND_INVIDIOUS);
    }

    /** Rows of a comments page (reused by getCommentsPage and parseMoreComments). */
    private List<Comment> parseCommentRows(JSONArray arr, String videoId) throws IOException {
        List<Comment> comments = new ArrayList<Comment>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            try {
                comments.add(parseCommentRow(j, videoId));
            } catch (JSONException ignored) {
                // Skip a malformed comment row instead of losing the whole list.
            }
        }
        return comments;
    }

    /**
     * Parses a further comments page (?continuation=...); same schema as
     * the first page. Called by Manager when the UI scrolls to the end.
     */
    CommentsPage parseMoreComments(String body, String videoId) throws IOException {
        JSONObject root = JSON.getObject(body);
        try {
            String error = root.getString("error");
            if (error != null && error.length() > 0) {
                throw new IOException("Comments page error: " + error);
            }
        } catch (JSONException ignored) {}
        List<Comment> rows = parseCommentRows(root.getArray("comments"), videoId);
        String moreUrl = null;
        String continuation = root.getNullableString("continuation");
        if (continuation != null && continuation.length() > 0 && !rows.isEmpty()) {
            try {
                moreUrl = baseUrl + "/api/v1/comments/" + videoId
                        + "?continuation=" + URLEncoder.encode(continuation, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ignored) {
                moreUrl = baseUrl + "/api/v1/comments/" + videoId + "?continuation=" + continuation;
            }
        }
        return new CommentsPage(rows, moreUrl, Comment.BACKEND_INVIDIOUS);
    }

    /**
     * One comment row of /api/v1/comments. Reply metadata comes in two
     * flavors depending on the Invidious build: modern {replyCount,
     * continuation} (fetched via ?continuation=... on expand) or a legacy
     * inline replies array (no network needed).
     */
    private Comment parseCommentRow(JSONObject j, String videoId) throws JSONException {
        long published = j.getLong("published");
        Comment c = new Comment(j.getString("author"), commentAvatarUrl(j),
                j.getString("content"), published > 0 ? new Date(published * 1000L) : null,
                j.getString("authorId"));
        c.backend = Comment.BACKEND_INVIDIOUS;
        // Pinned flag: modern builds say "isPinned", older ones "pinned".
        c.pinned = j.getBoolean("isPinned", j.getBoolean("pinned", false));
        c.replyCount = j.getInt("replyCount", 0);
        // Modern builds: {replyCount, continuation}.
        JSONObject repliesObj = null;
        try {
            repliesObj = j.getObject("replies");
        } catch (JSONException notObject) {
            repliesObj = null;
        }
        if (repliesObj != null) {
            int count = repliesObj.getInt("replyCount", 0);
            if (count > c.replyCount) c.replyCount = count;
            String continuation = repliesObj.getNullableString("continuation");
            if (continuation != null && continuation.length() > 0) {
                try {
                    c.repliesUrl = baseUrl + "/api/v1/comments/" + videoId
                            + "?continuation=" + URLEncoder.encode(continuation, "UTF-8");
                } catch (java.io.UnsupportedEncodingException ignored) {
                    c.repliesUrl = baseUrl + "/api/v1/comments/" + videoId
                            + "?continuation=" + continuation;
                }
            }
        } else {
            // Legacy builds: inline array of reply rows.
            try {
                JSONArray repliesArr = j.getArray("replies");
                List<Comment> replies = new ArrayList<Comment>();
                for (int r = 0; r < repliesArr.size(); r++) {
                    JSONObject row = repliesArr.getObject(r);
                    Comment reply = parseCommentRow(row, videoId);
                    reply.isReply = true;
                    replies.add(reply);
                }
                if (!replies.isEmpty()) {
                    c.preloadedReplies = replies;
                    if (c.replyCount <= 0) c.replyCount = replies.size();
                }
            } catch (JSONException ignored) {
                // No legacy replies array either: no replies.
            }
        }
        return c;
    }

    /**
     * Parses one replies page (?continuation=...); the rows reuse the same
     * schema as the top-level comments. Called by Manager on expand.
     */
    List<Comment> parseReplies(String json) throws IOException {
        JSONObject root = JSON.getObject(json);
        try {
            String error = root.getString("error");
            if (error != null && error.length() > 0) {
                throw new IOException("Comment replies error: " + error);
            }
        } catch (JSONException ignored) {}
        JSONArray arr = root.getArray("comments");
        List<Comment> replies = new ArrayList<Comment>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            try {
                long published = j.getLong("published");
                Comment reply = new Comment(j.getString("author"), commentAvatarUrl(j),
                        j.getString("content"), published > 0 ? new Date(published * 1000L) : null,
                        j.getString("authorId"));
                reply.backend = Comment.BACKEND_INVIDIOUS;
                reply.isReply = true;
                replies.add(reply);
            } catch (JSONException ignored) {
                // Skip a broken row, keep the rest.
            }
        }
        return replies;
    }

    @Override public List<VideoInfo> getRelated(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/videos/"+id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req, HttpClient.VIDEO_TIMEOUT));
        List<VideoInfo> related = new ArrayList<VideoInfo>();
        JSONArray arr = json.getArray("recommendedVideos");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            Date published; try {
                published = sdf.parse(j.getString("published"));
            } catch(Exception e) {
                e.printStackTrace(); published = new Date();
            }
            related.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                    videoThumbUrl(j),
                    j.getString("author"), "", j.getString("authorId"), Utils.formatDuration(j.getInt("lengthSeconds")),
                    Utils.parseTextCount(j.getString("viewCountText")), published));
        }
        return related;
    }

    @Override
    public String getThumbnail(String id) {
        return baseUrl + "/vi/" + id + "/mqdefault.jpg";
    }

    @Override
    public Channel getChannel(String id) throws IOException {
        if (!id.startsWith("UC")) {
            id = JSON.getArray(HttpClient.executeToString(
                    new HttpRequest.Builder(baseUrl, "/api/v1/search")
                        .addParam("q", id)
                        .addParam("type", "channel").build()
                    ))
                    .getObject(0).getString("authorId");
        }
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/channels/"+id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        try {
            if (json.getString("error").length() > 0)
                throw new ContentUnavailableException("Channel unavailable");
        } catch (JSONException ignored) {}
        List<VideoInfo> videos = parseChannelVideos(json.getArray("latestVideos"), id);
        JSONArray banners = json.getArray("authorBanners");
        String banner = "";
        if (!banners.isEmpty())
            banner = Utils.parseUrl(baseUrl + "/ggpht", banners.getObject(0).getString("url").replace("w2560", "w900"));
        return new Channel(id, json.getString("author"),
                Utils.parseUrl(baseUrl + "/ggpht", json.getArray("authorThumbnails").getObject(AUTHOR_THUMB).getString("url")), banner,
                json.getString("description"), json.getInt("subCount"), videos);
    }

    @Override
    public String getChannelIcon(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/channels/"+id);
        return Utils.parseUrl(baseUrl + "/ggpht", JSON.getObject(HttpClient.executeToString(req)).getArray("authorThumbnails").getObject(AUTHOR_THUMB).getString("url"));
    }

    @Override
    public List<VideoInfo> getChannelVideos(String id, int sort) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/api/v1/channels/" + id + "/videos")
                .addParam("sort_by", sort == 1 ? "popular" : "newest").build();
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        List<VideoInfo> videos = parseChannelVideos(json.getArray("videos"), id);
        if (videos.isEmpty()) {
            if (sort == 1) // At this stage, we can't get popular channel videos
                return videos;
            // If the instance can't parse videos from the channel page, we'll use channel playlist instead
            req = new HttpRequest(baseUrl, "/api/v1/playlists/UU" + id.substring(2));
            JSONArray arr = JSON.getObject(HttpClient.executeToString(req)).getArray("videos");
            for (int i = 0; i < arr.size(); i++) {
                JSONObject j = arr.getObject(i);
                videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                        videoThumbUrl(j),
                        null, null, id, Utils.formatDuration(j.getInt("lengthSeconds")), -1, null));
            }
        }
        return videos;
    }

    /**
     * Public playlists of a channel. Invidious serves them at
     * /api/v1/channels/{id}/playlists with title/playlistId/
     * playlistThumbnail/videoCount per item.
     */
    @Override
    public List<ChannelPlaylist> getChannelPlaylists(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/channels/" + id + "/playlists");
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        try {
            String error = json.getString("error");
            if (error != null && error.length() > 0)
                throw new IOException("Channel playlists error: " + error);
        } catch (JSONException ignored) {}
        JSONArray arr = json.getArray("playlists");
        List<ChannelPlaylist> playlists = new ArrayList<ChannelPlaylist>();
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject j = arr.getObject(i);
                playlists.add(new ChannelPlaylist(
                        j.getString("playlistId"),
                        j.getString("title"),
                        playlistThumb(j.getString("playlistThumbnail")),
                        j.getInt("videoCount", 0)));
            } catch (JSONException ignored) {
                // Skip a broken row, keep the rest.
            }
        }
        return playlists;
    }

    /**
     * Rebuilds a playlist thumbnail as a mqdefault URL served through the
     * instance's /vi/ image proxy (same path used by getThumbnail), so the
     * app never talks to Google servers directly. Falls back to routing the
     * original absolute URL through the instance.
     */
    private String playlistThumb(String raw) {
        if (raw == null || raw.length() == 0) return "";
        int i = raw.indexOf("/vi/");
        if (i != -1) {
            String rest = raw.substring(i + 4);
            int slash = rest.indexOf('/');
            if (slash > 0) {
                return baseUrl + "/vi/" + rest.substring(0, slash + 1) + "mqdefault.jpg";
            }
        }
        return Utils.parseUrl(baseUrl, raw);
    }

    /** Videos of one playlist (PL...). Playlist listings carry no view
     *  counts or upload dates, so those are simply marked unknown. */
    @Override
    public List<VideoInfo> getPlaylistVideos(String playlistId) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/playlists/" + playlistId);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        try {
            String error = json.getString("error");
            if (error != null && error.length() > 0)
                throw new IOException("Playlist error: " + error);
        } catch (JSONException ignored) {}
        JSONArray arr = json.getArray("videos");
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            try {
                if (!"video".equals(j.getString("type"))) continue;
                String author;
                try {
                    author = j.getString("author");
                } catch (JSONException ignored) { author = null; }
                String authorId;
                try {
                    authorId = j.getString("authorId");
                } catch (JSONException ignored) { authorId = ""; }
                videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                        videoThumbUrl(j),
                        author, null, authorId, Utils.formatDuration(j.getInt("lengthSeconds")), -1, null));
            } catch (JSONException ignored) {
                // Skip a broken row, keep the rest.
            }
        }
        return videos;
    }

    /**
     * Caption tracks via /api/v1/captions/{id}. Invidious serves WebVTT
     * through the per-track url (relative to the instance), so subtitles
     * work even on devices where the player cannot render anything but
     * raw video.
     */
    @Override
    public List<SubtitleTrack> getSubtitles(String videoId) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/api/v1/captions/" + videoId);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        try {
            String error = json.getString("error");
            if (error != null && error.length() > 0)
                throw new IOException("Captions error: " + error);
        } catch (JSONException ignored) {}
        JSONArray arr = json.getArray("captions");
        List<SubtitleTrack> tracks = new ArrayList<SubtitleTrack>();
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject j = arr.getObject(i);
                String url = j.getString("url");
                if (url == null || url.length() == 0) continue;
                tracks.add(new SubtitleTrack(j.getString("label"), j.getString("languageCode"),
                        url.startsWith("http") ? url : baseUrl + url));
            } catch (JSONException ignored) {
                // Skip a broken row, keep the rest.
            }
        }
        return tracks;
    }

    /**
     * Avatar URL of one comment row, routed through the /ggpht image proxy.
     * Newer builds serve a single absolute "authorThumbnail" string (the
     * plural array is gone); older builds still send the array.
     */
    private String commentAvatarUrl(JSONObject j) {
        // New schema: single string, usually https://yt3.ggpht.com/... or
        // https://yt3.googleusercontent.com/...
        String t = j.getNullableString("authorThumbnail");
        if (t != null && t.length() > 0) {
            return Utils.parseUrl(baseUrl + "/ggpht", t);
        }
        // Legacy schema: array of {url,width,height} objects.
        try {
            String url = j.getArray("authorThumbnails").getObject(0).getString("url");
            if (url != null && url.length() > 0) {
                return Utils.parseUrl(baseUrl + "/ggpht", url);
            }
        } catch (JSONException ignored) {}
        return null;
    }

    private List<VideoInfo> parseChannelVideos(JSONArray arr, String channelId) {
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject j = arr.getObject(i);
                String type = j.getString("type");
                // Some instances omit the author on channel listings.
                String author = null;
                try {
                    author = j.getString("author");
                } catch (JSONException ignored) {}
                if (type.equals("video"))
                    videos.add(new VideoInfo(j.getString("videoId"), j.getString("title"),
                        videoThumbUrl(j),
                        author, null, channelId,
                            Utils.formatDuration(j.getInt("lengthSeconds")), j.getLong("viewCount"), new Date(j.getLong("published") * 1000L)
                    ));
                else if (type.equals("playlist")) { // sometimes videos are returned as playlists, but with valid info
                    String videoId = j.getString("playlistId");
                    videos.add(new VideoInfo(videoId, j.getString("title"),
                            baseUrl + "/vi/" + videoId + "/mqdefault.jpg", "", null, channelId, "", -1, null
                    ));
                }
            } catch(Exception e) { e.printStackTrace(); }
        }
        return videos;
    }
}
