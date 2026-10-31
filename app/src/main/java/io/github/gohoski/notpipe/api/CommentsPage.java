package io.github.gohoski.notpipe.api;

import java.util.ArrayList;
import java.util.List;

import io.github.gohoski.notpipe.data.Comment;

/**
 * One page of comments plus everything needed to continue: the absolute
 * URL of the next page (built by the API class that produced this one,
 * same scheme as comment replies) and the backend tag that selects the
 * JSON schema when parsing it. Mirrors NewPipe's comments pagination:
 * the UI keeps appending pages until moreUrl is null.
 */
public class CommentsPage {
    public final List<Comment> comments;
    /** Absolute URL returning the next page, or null after the last one. */
    public final String moreUrl;
    /** Comment.BACKEND_* of the instance that produced this page. */
    public final int backend;

    public CommentsPage(List<Comment> comments, String moreUrl, int backend) {
        this.comments = comments != null ? comments : new ArrayList<Comment>();
        this.moreUrl = moreUrl;
        this.backend = backend;
    }
}
