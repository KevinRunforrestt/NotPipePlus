package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import cc.nnproject.json.JSON;
import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONException;
import cc.nnproject.json.JSONObject;
import io.github.gohoski.notpipe.Utils;
import io.github.gohoski.notpipe.data.Channel;
import io.github.gohoski.notpipe.data.ChannelPlaylist;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.data.SubtitleTrack;
import io.github.gohoski.notpipe.data.Video;
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/**
 * Created by Gleb on 05.06.2026.
 */
class Piped implements Metadata, VideoStream {
    private String baseUrl, proxyUrl;
    Piped(String baseUrl, String proxyUrl) {
        this.baseUrl = baseUrl;
        this.proxyUrl = proxyUrl;
    }

    public String getName() { return "Piped"; }
    public String getHost() {
        return replaceAll(replaceAll(baseUrl, "https://", ""), "http://", "");
    }

    /** Video thumbnail served through the instance image proxy. */
    private String thumbUrl(String videoId) {
        return thumbUrl(videoId);
    }

    /** Avatar URL routed through the image proxy (webp -> jpg). */
    private String avatarUrl(String raw) {
        return Utils.parseUrl(proxyUrl, replaceAll(raw, "-no-rw?host=", "-no-rj?host=") + "&rewrite=false");
    }

    /** Comment avatar through the proxy: comment rows advertise webp
     *  more often than other rows, so both suffixes are upgraded to jpg. */
    private String commentAvatarUrl(String raw) {
        if (raw == null || raw.length() == 0) return null;
        return Utils.parseUrl(proxyUrl, replaceAll(replaceAll(raw, "-no-rw?host=", "-no-rj?host="), "-rw?host=", "-rj?host=") + "&rewrite=false");
    }

    /**
     * Popular feed: the hype playlist first (only counts when it has
     * items), then /trending?region=US. An empty result throws so the
     * instance chain advances instead of painting "no videos"; one bad
     * row never kills the feed (per-item try/catch).
     */
    @Override
    public List<VideoInfo> getPopularVideos() throws IOException {
        List<VideoInfo> videos = new ArrayList<VideoInfo>();

        // 1. Hype playlist (only counts when it actually has items).
        try {
            HttpRequest req = new HttpRequest(baseUrl, "/playlists/" + Utils.getHypePlaylist());
            JSONArray arr = JSON.getObject(HttpClient.executeToString(req)).getArray("relatedStreams");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    try {
                        JSONObject j = arr.getObject(i);
                        String videoId = j.getString("url").substring(9);
                        videos.add(new VideoInfo(videoId, j.getString("title"),
                                thumbUrl(videoId),
                                j.getString("uploaderName"), "", j.getString("uploaderUrl").substring(9),
                                Utils.formatDuration(j.getInt("duration")),
                                j.getLong("views"), new Date(j.getLong("uploaded"))));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!videos.isEmpty()) return videos;

        // 2. Trending (region US) — the array is the whole response.
        try {
            HttpRequest req = new HttpRequest.Builder(baseUrl, "/trending")
                    .addParam("region", "US").build();
            JSONArray arr = JSON.getArray(HttpClient.executeToString(req));
            for (int i = 0; i < arr.size(); i++) {
                try {
                    JSONObject j = arr.getObject(i);
                    // "stream" rows are the videos; channel/playlist rows skip.
                    if (!"stream".equals(j.getString("type", ""))) continue;
                    String url = j.getString("url");
                    String videoId = url.startsWith("/watch?v=") ? url.substring(9) : url;
                    String uploaderUrl = j.getString("uploaderUrl");
                    videos.add(new VideoInfo(videoId, j.getString("title"),
                            thumbUrl(videoId),
                            j.getString("uploaderName"), "",
                            uploaderUrl != null && uploaderUrl.startsWith("/channel/")
                                    ? uploaderUrl.substring(9) : "",
                            Utils.formatDuration(j.getInt("duration")),
                            j.getLong("views"), new Date(j.getLong("uploaded"))));
                } catch (Throwable ignored) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (videos.isEmpty()) {
            throw new IOException("Popular feed unavailable on " + getHost());
        }
        return videos;
    }

    @Override
    public List<VideoInfo> search(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/search").addParam("q", q).addParam("filter", "videos").build();
        JSONArray arr = JSON.getObject(HttpClient.executeToString(req)).getArray("items");
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            String videoId = j.getString("url").substring(9);
            videos.add(new VideoInfo(videoId, j.getString("title"),
                    thumbUrl(videoId), j.getString("uploaderName"),
                    avatarUrl(j.getString("uploaderAvatar")),
                    j.getString("uploaderUrl").substring(9), Utils.formatDuration(j.getInt("duration")),
                    j.getLong("views"), new Date(j.getLong("uploaded"))));
        }
        return videos;
    }

    @Override
    public List<String> searchSuggestions(String q) throws IOException {
        HttpRequest req = new HttpRequest.Builder(baseUrl, "/suggestions").addParam("query", q).build();
        JSONArray arr = JSON.getArray(HttpClient.executeToString(req));
        List<String> s = new ArrayList<String>();
        for (int i = 0; i < arr.size(); i++) {
            s.add(arr.getString(i));
        } return s;
    }

    @Override
    public Video getVideo(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/streams/" + id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        try {
            String error = json.getString("error"), message = json.getString("message");
            if (error.length() > 0 && !(message.contains("bot") || message.contains("protect") || message.contains("page"))) {
                throw new ContentUnavailableException(message);
            }
        } catch (JSONException ignored) {}
        List<VideoInfo> related = new ArrayList<VideoInfo>();
        JSONArray arr = json.getArray("relatedStreams");
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            String videoId = j.getString("url").substring(9);
            related.add(new VideoInfo(videoId, j.getString("title"),
                    thumbUrl(videoId), j.getString("uploaderName"),
                    avatarUrl(j.getString("uploaderAvatar")),
                    j.getString("uploaderUrl").substring(9), Utils.formatDuration(j.getInt("duration")),
                    j.getLong("views"), new Date(j.getLong("uploaded"))));
        }
        JSONArray videoStreams = json.getArray("videoStreams");
        return new Video(id, json.getString("title"), thumbUrl(id),
                json.getString("uploader"), avatarUrl(json.getString("uploaderAvatar")),
                json.getString("uploaderUrl").substring(9), json.getInt("duration"), json.getLong("views"),
                new Date(json.getLong("uploaded")), cleanHtml(json.getString("description")), json.getInt("likes"), json.getInt("uploaderSubscriberCount"),
                Utils.parseUrl(proxyUrl, videoStreams.getObject(videoStreams.size()-1).getString("url")), related, null);
    }

    @Override
    public String getVideoUrl(String id, String ignored, int ignored2) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/streams/" + id);
        JSONArray videoStreams = JSON.getObject(HttpClient.executeToString(req)).getArray("videoStreams");
        return Utils.parseUrl(proxyUrl, videoStreams.getObject(videoStreams.size()-1).getString("url"));
    }

    @Override
    public List<Comment> getComments(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/comments/" + id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        // Newer Piped builds answer with {"comments":[],...,"disabled":true}
        // for videos where YouTube disabled comments: that is a definitive
        // "no comments", not an error, so return the empty list as is.
        if (json.has("error")) {
            String error = json.getString("error");
            if (error != null && error.length() > 0
                    && (error.toLowerCase().contains("disabled") || error.toLowerCase().contains("unavailable"))) {
                return new ArrayList<Comment>();
            }
            // Other errors (rate limits, broken extraction): let the Manager
            // retry with a different instance / frontend.
            throw new IOException("Comments error: " + error);
        }
        JSONArray arr = json.getArray("comments");
        List<Comment> comments = new ArrayList<Comment>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            try {
                comments.add(parseCommentRow(j, id));
            } catch (JSONException ignored) {
                // Skip a malformed comment row, keep the rest.
            }
        }
        // A positive advertised count with zero extracted rows means this
        // instance failed the extraction: let the Manager move on.
        if (comments.isEmpty() && !json.getBoolean("disabled", false)) {
            long commentCount = -1;
            try {
                commentCount = json.getLong("commentCount");
            } catch (JSONException ignored) {}
            if (commentCount > 0) {
                throw new IOException("Comments extraction failed on this instance");
            }
        }
        return comments;
    }

    /**
     * One comment row of /comments/{id}. Replies are advertised as a count
     * plus a "repliesPage" continuation token, fetched on expand
     * (NewPipe's behavior).
     */
    private Comment parseCommentRow(JSONObject j, String videoId) throws JSONException {
        // Author thumbnails are JSON null on many instances: keep the
        // avatar empty instead of building a broken "null-..." URL.
        String avatar = null;
        String thumb = j.getNullableString("thumbnail");
        if (thumb != null && thumb.length() > 0) {
            avatar = commentAvatarUrl(thumb);
        }
        String author;
        try {
            author = j.getString("author");
        } catch (JSONException ignored) { author = ""; }
        String text;
        try {
            text = cleanHtml(j.getString("commentText"));
        } catch (JSONException ignored) { text = ""; }
        int replyCount = j.getInt("replyCount", 0);
        String repliesPage = j.getNullableString("repliesPage");
        String repliesUrl = null;
        if (repliesPage != null && repliesPage.length() > 0) {
            try {
                repliesUrl = baseUrl + "/comments/" + videoId
                        + "?nextpage=" + java.net.URLEncoder.encode(repliesPage, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ignored) {
                repliesUrl = baseUrl + "/comments/" + videoId + "?nextpage=" + repliesPage;
            }
        }
        Comment c = new Comment(author, avatar, text,
                Utils.parseRelativeDate(j.getString("commentedTime")), j.getString("commentorUrl").substring(9),
                replyCount, repliesUrl, Comment.BACKEND_PIPED);
        return c;
    }

    /**
     * Parses one replies page (?nextpage=...) into Comment rows; the schema
     * matches the top-level comment rows. Called by
     * Manager.parseCommentReplies when the user expands a comment.
     */
    List<Comment> parseReplies(String json) throws IOException {
        JSONObject root = JSON.getObject(json);
        if (root.has("error")) {
            String error = root.getString("error");
            if (error != null && error.length() > 0) {
                throw new IOException("Comment replies error: " + error);
            }
        }
        JSONArray arr = root.getArray("comments");
        List<Comment> replies = new ArrayList<Comment>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            try {
                Comment reply = parseReplyRow(j);
                reply.isReply = true;
                replies.add(reply);
            } catch (JSONException ignored) {
                // Skip a malformed row, keep the rest.
            }
        }
        return replies;
    }

    private Comment parseReplyRow(JSONObject j) throws JSONException {
        // Same avatar handling as top-level rows.
        String avatar = null;
        String thumb = j.getNullableString("thumbnail");
        if (thumb != null && thumb.length() > 0) {
            avatar = commentAvatarUrl(thumb);
        }
        String author;
        try {
            author = j.getString("author");
        } catch (JSONException ignored) { author = ""; }
        String text;
        try {
            text = cleanHtml(j.getString("commentText"));
        } catch (JSONException ignored) { text = ""; }
        Date date;
        try {
            date = Utils.parseRelativeDate(j.getString("commentedTime"));
        } catch (JSONException ignored) { date = null; }
        String channelId = "";
        try {
            channelId = j.getString("commentorUrl").substring(9);
        } catch (JSONException ignored) {}
        Comment reply = new Comment(author, avatar, text, date, channelId,
                0, null, Comment.BACKEND_PIPED);
        reply.isReply = true;
        return reply;
    }

    /**
     * Public playlists created by a channel: not exposed by this frontend
     * in a parseable shape — throw so the Manager falls back to Invidious
     * (whose /api/v1/channels/{id}/playlists serves them natively).
     */
    @Override
    public List<ChannelPlaylist> getChannelPlaylists(String id) throws IOException {
        throw new IOException("Channel playlists not supported on Piped");
    }

    /** Videos of one playlist (PL... id) via /playlists/{id}. */
    @Override
    public List<VideoInfo> getPlaylistVideos(String playlistId) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/playlists/" + playlistId);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        try {
            String error = json.getString("error");
            if (error != null && error.length() > 0)
                throw new IOException("Playlist error: " + error);
        } catch (JSONException ignored) {}
        JSONArray arr = json.getArray("relatedStreams");
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            try {
                String videoId = j.getString("url").substring(9);
                String uploader = null;
                try {
                    uploader = j.getString("uploaderName");
                } catch (JSONException ignored) {}
                String uploaderUrl = null;
                try {
                    uploaderUrl = j.getString("uploaderUrl");
                } catch (JSONException ignored) {}
                videos.add(new VideoInfo(videoId, j.getString("title"),
                        thumbUrl(videoId),
                        uploader, null,
                        uploaderUrl != null && uploaderUrl.length() > 9 ? uploaderUrl.substring(9) : "",
                        Utils.formatDuration(j.getInt("duration")), -1, null));
            } catch (JSONException ignored) {
                // Skip a broken row, keep the rest.
            }
        }
        return videos;
    }

    /**
     * Caption tracks via /streams/{id}.subtitles[]. The URLs are YouTube
     * timedtext links served through the instance proxy and default to
     * TTML; asking for fmt=vtt returns plain WebVTT, which the built-in
     * renderer can parse.
     */
    @Override
    public List<SubtitleTrack> getSubtitles(String videoId) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/streams/" + videoId);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        if (json.has("error"))
            throw new IOException("Streams error: " + json.getString("error"));
        JSONArray arr = json.getArray("subtitles");
        List<SubtitleTrack> tracks = new ArrayList<SubtitleTrack>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            try {
                String url = j.getString("url");
                if (url == null || url.length() == 0) continue;
                // Some builds advertise the caption as a path on their own
                // proxy ("/api/timedtext?...") instead of a full URL.
                if (url.startsWith("/")) {
                    url = proxyUrl + url;
                }
                // WebVTT is far lighter to parse than TTML on old devices.
                // The raw URLs announce TTML ("fmt=ttml"); asking the proxy
                // for fmt=vtt returns real WebVTT. Some builds omit the fmt
                // parameter entirely and then serve TTML — ask for VTT too.
                if (url.contains("fmt=ttml")) {
                    url = replaceAll(url, "fmt=ttml", "fmt=vtt");
                } else if (url.indexOf("timedtext") != -1 && url.indexOf("fmt=") == -1) {
                    url = url + (url.indexOf('?') != -1 ? "&" : "?") + "fmt=vtt";
                }
                tracks.add(new SubtitleTrack(j.getString("name"), j.getString("code"), url));
            } catch (JSONException ignored) {
                // Skip a broken row, keep the rest.
            }
        }
        return tracks;
    }

    @Override
    public List<VideoInfo> getRelated(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/streams/" + id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        List<VideoInfo> related = new ArrayList<VideoInfo>();
        JSONArray arr = json.getArray("relatedStreams");
        for (int i = 0; i < arr.size(); i++) {
            JSONObject j = arr.getObject(i);
            String videoId = j.getString("url").substring(9);
            related.add(new VideoInfo(videoId, j.getString("title"),
                    thumbUrl(videoId), j.getString("uploaderName"),
                    avatarUrl(j.getString("uploaderAvatar")),
                    j.getString("uploaderUrl").substring(9), Utils.formatDuration(j.getInt("duration")),
                    j.getLong("views"), new Date(j.getLong("uploaded"))));
        }
        return related;
    }

    @Override
    public String getThumbnail(String id) {
        return thumbUrl(id);
    }

    @Override
    public Channel getChannel(String id) throws IOException {
        if (!id.startsWith("UC")) {
            id = JSON.getArray(HttpClient.executeToString(
                    new HttpRequest.Builder(baseUrl, "/search")
                            .addParam("q", id)
                            .addParam("filter", "channels").build()
            ))
                    .getObject(0).getString("url").substring(9);
        }
        HttpRequest req = new HttpRequest(baseUrl, "/channel/" + id);
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        try {
            if (json.getString("error").length() > 0)
                throw new ContentUnavailableException(json.getString("message"));
        } catch (JSONException ignored) {}
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        JSONArray arr = json.getArray("relatedStreams");
        String thumbnail = avatarUrl(json.getString("avatarUrl"));
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject j = arr.getObject(i);
                String videoId = j.getString("url").substring(9);
                videos.add(new VideoInfo(videoId, j.getString("title"),
                        thumbUrl(videoId), j.getString("uploaderName"),
                        thumbnail, id, Utils.formatDuration(j.getInt("duration")),
                        j.getLong("views"), new Date(j.getLong("uploaded"))));
            } catch(Exception e) { e.printStackTrace(); }
        }
        return new Channel(json.getString("id"), json.getString("name"), thumbnail,
                Utils.parseUrl(proxyUrl, replaceAll(replaceAll(json.getString("bannerUrl"), "w2560", "w900"), "-rw?host=", "-rj?host=") + "&rewrite=false"),
                json.getString("description"), json.getInt("subscriberCount"), videos);
    }

    @Override
    public String getChannelIcon(String id) throws IOException {
        HttpRequest req = new HttpRequest(baseUrl, "/channel/"+id);
        return avatarUrl(JSON.getObject(HttpClient.executeToString(req)).getString("avatarUrl"));
    }

    @Override
    public List<VideoInfo> getChannelVideos(String id, int sort) throws IOException {
        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        if (sort != 0) // We can get only latest channel videos
            return videos;
        HttpRequest req = new HttpRequest(baseUrl, "/playlists/UU" + id.substring(2));
        JSONObject json = JSON.getObject(HttpClient.executeToString(req));
        JSONArray arr = json.getArray("relatedStreams");
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject j = arr.getObject(i);
                String videoId = j.getString("url").substring(9);
                videos.add(new VideoInfo(videoId, j.getString("title"),
                        thumbUrl(videoId), null, null, null,
                        Utils.formatDuration(j.getInt("duration")), j.getLong("views"), new Date(j.getLong("uploaded"))));
            } catch(Exception e) { e.printStackTrace(); }
        }
        return videos;
    }

    /**
     * Helper to parse Piped's HTML description safely and allocation-free.
     */
    private static String cleanHtml(String html) {
        if (html == null) return null;

        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = html.length();

        while (i < len) {
            int tagStart = html.indexOf('<', i);
            if (tagStart == -1) {
                sb.append(html, i, len); // Efficient append without substring allocation
                break;
            }

            sb.append(html, i, tagStart);

            int tagEnd = html.indexOf('>', tagStart);
            if (tagEnd == -1) {
                sb.append(html, tagStart, len);
                break;
            }

            // Case-insensitive comparisons matching starting tags directly within parent string to avoid allocations
            if (html.regionMatches(true, tagStart, "<br", 0, 3)) {
                sb.append("\n");
                i = tagEnd + 1;
            } else if (html.regionMatches(true, tagStart, "<a", 0, 2)) {
                int closeTagStart = indexOfIgnoreCase(html, "</a>", tagEnd + 1, len);
                if (closeTagStart == -1) {
                    i = tagEnd + 1;
                    continue;
                }

                String href = extractAttribute(html, tagStart, tagEnd + 1, "href");
                String innerText = html.substring(tagEnd + 1, closeTagStart);

                // Recursively clean inner text
                innerText = cleanHtml(innerText);

                if (href != null && href.length() > 0) {
                    href = decodeHtmlEntities(href);

                    boolean isHashtag = href.contains("/hashtag/");
                    boolean isTimestamp = (href.contains("youtube.com") || href.contains("youtu.be"))
                            && (href.contains("t=") || href.contains("time_continue="));

                    if (isHashtag || isTimestamp) {
                        sb.append(innerText);
                    } else {
                        String cleanInnerText = innerText.trim();
                        if (isUrlOrTruncated(cleanInnerText)) {
                            sb.append(href);
                        } else {
                            sb.append(cleanInnerText).append(" (").append(href).append(")");
                        }
                    }
                } else {
                    sb.append(innerText);
                }

                i = closeTagStart + 4; // Skip past </a>
            } else {
                i = tagEnd + 1; // Strip other html tags
            }
        }

        return decodeHtmlEntities(sb.toString());
    }

    /**
     * Extracts an attribute from within a bounded HTML tag string, allocation-free.
     */
    private static String extractAttribute(String html, int start, int end, String attr) {
        String attrMatch = attr + "=";
        int idx = indexOfIgnoreCase(html, attrMatch, start, end);
        if (idx == -1) return null;

        int valStart = idx + attrMatch.length();
        if (valStart >= end) return null;

        char quote = html.charAt(valStart);
        if (quote == '"' || quote == '\'') {
            int valEnd = -1;
            for (int j = valStart + 1; j < end; j++) {
                if (html.charAt(j) == quote) {
                    valEnd = j;
                    break;
                }
            }
            if (valEnd != -1) {
                return html.substring(valStart + 1, valEnd);
            }
        } else {
            int valEnd = valStart;
            while (valEnd < end) {
                char c = html.charAt(valEnd);
                if (c == ' ' || c == '>') {
                    break;
                }
                valEnd++;
            }
            return html.substring(valStart, valEnd);
        }
        return null;
    }

    /**
     * Determines if the anchor's inner text is a literal representation of a URL.
     */
    private static boolean isUrlOrTruncated(String text) {
        String lower = text.toLowerCase();
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) {
            return true;
        }
        if (text.contains(".") && !text.contains(" ")) {
            return true;
        }
        if (text.contains("...")) {
            int dotDotDot = text.indexOf("...");
            String prefix = lower.substring(0, dotDotDot);
            if (prefix.contains(".") || prefix.contains("/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Safe HTML entity decoding using highly efficient custom replaceAll implementation.
     */
    private static String decodeHtmlEntities(String text) {
        if (text == null) return null;
        text = replaceAll(text, "&amp;", "&");
        text = replaceAll(text, "&nbsp;", " ");
        text = replaceAll(text, "&lt;", "<");
        text = replaceAll(text, "&gt;", ">");
        text = replaceAll(text, "&apos;", "'");
        text = replaceAll(text, "&quot;", "\"");
        return text;
    }

    /**
     * High-performance, memory-friendly non-regex replacement engine for Java 1.5+.
     * Resolves major performance penalties encountered in legacy standard String.replace.
     */
    private static String replaceAll(String src, String target, String replacement) {
        if (src == null || target == null || replacement == null || target.length() == 0) {
            return src;
        }
        int idx = src.indexOf(target);
        if (idx == -1) {
            return src; // Match not found: prevents instantiation of unnecessary StringBuilder
        }
        StringBuilder sb = new StringBuilder(src.length());
        int len = target.length();
        int prev = 0;
        while (idx != -1) {
            sb.append(src, prev, idx); // Appends characters directly without creating substring objects
            sb.append(replacement);
            prev = idx + len;
            idx = src.indexOf(target, prev);
        }
        sb.append(src, prev, src.length());
        return sb.toString();
    }

    /**
     * Allocation-free Case-Insensitive search utility within specified boundaries.
     */
    private static int indexOfIgnoreCase(String src, String target, int start, int end) {
        int targetLen = target.length();
        int max = end - targetLen;
        for (int i = start; i <= max; i++) {
            if (src.regionMatches(true, i, target, 0, targetLen)) {
                return i;
            }
        }
        return -1;
    }
}