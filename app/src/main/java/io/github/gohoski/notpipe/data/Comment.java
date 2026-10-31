package io.github.gohoski.notpipe.data;

import java.util.Date;
import java.util.List;

/**
 * One comment row, NewPipe-style: a top-level comment may carry a reply
 * count and a continuation to fetch its replies, which the UI expands and
 * collapses on demand (NewPipe's CommentsRepliesInfoItem), and may be
 * pinned by the creator (shown first with a "Pinned" badge, like NewPipe).
 *
 * The reply page URL is built by the API implementation that produced the
 * comment (Invidious ?continuation=..., Piped ?nextpage=...), so the fetch
 * needs no instance routing: the token is opaque and YouTube-side.
 */
public class Comment {
    /** Which frontend produced this comment (and therefore which JSON
     *  schema its replies page uses). */
    public static final int BACKEND_INVIDIOUS = 0;
    public static final int BACKEND_PIPED = 1;

    public final String channel, channelThumbnail, content, channelId;
    public final Date publishedAt;

    /** Replies of this comment; 0 = none, negative = unknown. */
    public int replyCount = 0;
    /** Absolute URL that returns the first page of replies, or null. */
    public String repliesUrl;
    /** BACKEND_* of the instance that listed this comment. */
    public int backend = BACKEND_INVIDIOUS;
    /** Legacy Invidious rows embed the replies inline: no network needed. */
    public List<Comment> preloadedReplies;
    /** True when this row is itself a reply (indented in the UI). */
    public boolean isReply = false;
    /** True when the creator pinned this comment (badge in the UI). */
    public boolean pinned = false;

    // Transient UI state of the replies toggle.
    public boolean repliesShown = false;
    public boolean repliesLoading = false;

    public Comment(String channel, String channelThumbnail, String content, Date publishedAt, String channelId) {
        this(channel, channelThumbnail, content, publishedAt, channelId, 0, null, 0);
    }

    public Comment(String channel, String channelThumbnail, String content, Date publishedAt,
            String channelId, int replyCount, String repliesUrl, int backend) {
        this.channel = channel;
        this.channelThumbnail = channelThumbnail;
        this.content = content;
        this.publishedAt = publishedAt;
        this.channelId = channelId;
        this.replyCount = replyCount;
        this.repliesUrl = repliesUrl;
        this.backend = backend;
    }
}
