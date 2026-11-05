package io.github.gohoski.notpipe.ui;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

import io.github.gohoski.notpipe.R;
import io.github.gohoski.notpipe.Utils;
import io.github.gohoski.notpipe.data.Comment;
import io.github.gohoski.notpipe.util.ChannelIconResolver;
import io.github.gohoski.notpipe.util.ImageLoader;

/** Adapter for comments with lazy loading and NewPipe-style replies. */
public class CommentAdapter extends ArrayAdapter<Comment> implements ChannelIconRefreshable {
    private Context context;

    /** Called when the user taps the replies toggle of a comment. */
    public interface OnRepliesToggleListener {
        void onToggleReplies(Comment comment);
    }

    /** Called when the user clicks the comment author's name or avatar. */
    public interface OnChannelClickListener {
        void onChannelClick(String channelId);
    }

    private OnRepliesToggleListener repliesListener;
    private OnChannelClickListener channelClickListener;
    private ChannelIconResolver.OnIconResolvedListener iconResolveListener;

    public CommentAdapter(Context context, int resource, List<Comment> objects) {
        super(context, resource, objects);
        this.context = context;
    }

    public void setOnRepliesToggleListener(OnRepliesToggleListener listener) {
        this.repliesListener = listener;
    }

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.channelClickListener = listener;
    }

    /** Shared fallback listener (notPipe 0.4 pattern): the activity refreshes rows once a resolution completes. */
    public void setChannelIconResolveListener(ChannelIconResolver.OnIconResolvedListener listener) {
        this.iconResolveListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        View row = convertView;

        if (row == null) {
            LayoutInflater inflater = ((Activity) context).getLayoutInflater();
            row = inflater.inflate(R.layout.item_comment, parent, false);
            holder = new ViewHolder();
            holder.channelTitle = (TextView) row.findViewById(R.id.channel_title);
            holder.content = (TextView) row.findViewById(R.id.content);
            holder.date = (TextView) row.findViewById(R.id.date);
            holder.channelThumb = (ImageView) row.findViewById(R.id.channel_thumbnail);
            holder.repliesToggle = (TextView) row.findViewById(R.id.replies_toggle);
            holder.pinnedBadge = (TextView) row.findViewById(R.id.pinned_badge);
            row.setTag(holder);
        } else {
            holder = (ViewHolder) row.getTag();
        }

        final Comment comment = getItem(position);
        holder.channelTitle.setText(comment.channel);
        holder.content.setText(comment.content);
        holder.date.setText(Utils.formatTimeAgo(context, comment.publishedAt));

        // Tapping the author's name or avatar opens their channel.
        final String commentChannelId = comment.channelId;
        View.OnClickListener channelClick = new View.OnClickListener() {
            public void onClick(View v) {
                if (channelClickListener != null && commentChannelId != null && commentChannelId.length() > 0) {
                    channelClickListener.onChannelClick(commentChannelId);
                }
            }
        };
        holder.channelTitle.setOnClickListener(channelClick);
        holder.channelThumb.setOnClickListener(channelClick);

        // NewPipe-style "Pinned" badge on creator-pinned comments.
        if (holder.pinnedBadge != null) {
            holder.pinnedBadge.setVisibility(comment.pinned ? View.VISIBLE : View.GONE);
        }

        // Replies render indented under their parent, NewPipe-style.
        int leftPad = (int) (comment.isReply ? 26f * context.getResources().getDisplayMetrics().density : 0f);
        row.setPadding(leftPad, row.getPaddingTop(), row.getPaddingRight(), row.getPaddingBottom());

        // The "View N replies" toggle, only on top-level comments that advertise replies.
        if (holder.repliesToggle != null) {
            if (!comment.isReply && comment.replyCount > 0) {
                holder.repliesToggle.setVisibility(View.VISIBLE);
                if (comment.repliesLoading) {
                    holder.repliesToggle.setText(R.string.replies_loading);
                } else if (comment.repliesShown) {
                    holder.repliesToggle.setText(R.string.hide_replies);
                } else {
                    holder.repliesToggle.setText(context.getString(R.string.view_replies, comment.replyCount));
                }
                holder.repliesToggle.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (repliesListener != null) {
                            repliesListener.onToggleReplies(comment);
                        }
                    }
                });
            } else {
                holder.repliesToggle.setVisibility(View.GONE);
                holder.repliesToggle.setOnClickListener(null);
            }
        }

        // Prepare holder for lazy loading. Same-comment rebinds keep their avatar (rebinds used to blank and reload it every time).
        boolean sameComment = holder.comment == comment;
        holder.comment = comment;
        if (!sameComment || !holder.imagesLoaded) {
            holder.imagesLoaded = false;
            holder.channelThumb.setImageResource(android.R.drawable.ic_menu_myplaces);
        } else {
            holder.imagesLoaded = true;
        }

        return row;
    }

    /** Called by VideoActivity when this row enters the screen. */
    public void loadImagesForView(View row) {
        if (row.getTag() instanceof ViewHolder) {
            final ViewHolder holder = (ViewHolder) row.getTag();
            if (holder.comment == null) return;
            ImageLoader.cancelLoad(holder.channelThumb);
            loadChannelIcon(holder);
        }
    }

    /** Loads the avatar: the row URL when present, otherwise the shared resolver. */
    private void loadChannelIcon(final ViewHolder holder) {
        if (holder.channelThumb == null || holder.comment == null) return;
        if (iconResolveListener != null) {
            ChannelIconResolver.loadInto(holder.channelThumb,
                    holder.comment.channelId, holder.comment.channelThumbnail,
                    iconResolveListener);
            holder.imagesLoaded = true;
            return;
        }
        String url = holder.comment.channelThumbnail;
        if (url != null && url.length() > 0) {
            ImageLoader.loadImage(url, holder.channelThumb);
            holder.imagesLoaded = true;
        } else {
            // No avatar URL on the row (Piped comment rows often carry a JSON-null thumbnail)
            String resolved = ChannelIconResolver.getResolved(holder.comment.channelId);
            if (resolved != null && !ChannelIconResolver.FAILED.equals(resolved)) {
                ImageLoader.loadImage(resolved, holder.channelThumb);
                holder.imagesLoaded = true;
            } else if (resolved == null) {
                // Kick off (or join) the background fetch; rebind the visible rows when it lands.
                ChannelIconResolver.requestFallback(holder.comment.channelId,
                        new ChannelIconResolver.OnIconResolvedListener() {
                            public void onIconResolved(String channelId, String iconUrl) {
                                if (iconUrl != null && iconUrl.length() > 0) {
                                    notifyDataSetChanged();
                                }
                            }
                        });
                holder.imagesLoaded = true;
            } else {
                // Already failed before: keep the placeholder.
                holder.imagesLoaded = true;
            }
        }
    }

    /** Specifically re-checks and loads the channel icon for a given row. */
    @Override
    public void updateChannelIconForView(View row, String resolvedChannelId) {
        if (row != null && row.getTag() instanceof ViewHolder) {
            ViewHolder holder = (ViewHolder) row.getTag();
            if (holder.comment != null && holder.imagesLoaded
                    && resolvedChannelId != null && resolvedChannelId.equals(holder.comment.channelId)) {
                loadChannelIcon(holder);
            }
        }
    }

    /** Reset image state for a view and cancel its pending loads so the hosting activity can be collected. */
    public void resetImageView(View row) {
        if (row != null && row.getTag() instanceof ViewHolder) {
            ViewHolder holder = (ViewHolder) row.getTag();
            if (holder.imagesLoaded) {
                holder.imagesLoaded = false;
                if (holder.channelThumb != null) {
                    ImageLoader.unbindImageView(holder.channelThumb);
                }
            }
        }
    }

    private static class ViewHolder {
        TextView channelTitle;
        TextView content;
        TextView date;
        TextView repliesToggle;
        TextView pinnedBadge;
        ImageView channelThumb;
        Comment comment;
        boolean imagesLoaded; // Tracks if we already triggered the load for this specific view
    }
}
