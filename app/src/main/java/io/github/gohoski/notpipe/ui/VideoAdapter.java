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
import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.util.ImageLoader;

/**
 * Adapter for video lists with lazy loading support: images load only
 * for rows explicitly marked as visible.
 */
public class VideoAdapter extends ArrayAdapter<VideoInfo> {
    private Context context;
    private int layoutResource;
    private boolean isInChannelActivity;
    private ChannelIconListener iconListener;

    public interface ChannelIconListener {
        String getResolvedIcon(String channelId);
        void onRequestFallbackIcon(String channelId);
    }

    public VideoAdapter(Context context, int resource, List<VideoInfo> objects) {
        super(context, resource, objects);
        this.context = context;
        this.layoutResource = resource;
        this.isInChannelActivity = false;
    }

    public VideoAdapter(Context context, int resource, List<VideoInfo> objects, boolean isInChannelActivity) {
        super(context, resource, objects);
        this.context = context;
        this.layoutResource = resource;
        this.isInChannelActivity = isInChannelActivity;
    }

    public void setChannelIconListener(ChannelIconListener listener) {
        this.iconListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        View row = convertView;

        boolean isLazyLoading = parent instanceof AdapterLinearLayout;

        if (row == null) {
            LayoutInflater inflater = ((Activity) context).getLayoutInflater();
            row = inflater.inflate(layoutResource, parent, false);
            holder = new ViewHolder();
            holder.videoTitle = (TextView) row.findViewById(R.id.video_title);
            holder.channelTitle = (TextView) row.findViewById(R.id.channel_title);
            holder.videoThumb = (ImageView) row.findViewById(R.id.video_thumbnail);
            holder.channelThumb = (ImageView) row.findViewById(R.id.channel_thumbnail);
            holder.duration = (TextView) row.findViewById(R.id.duration);
            row.setTag(holder);
        } else {
            holder = (ViewHolder) row.getTag();
        }

        final VideoInfo video = getItem(position);

        if (layoutResource == R.layout.item_video_compact && (video.views <= 0 || video.publishedAt == null)) {
            holder.videoTitle.setMaxLines(3);
        } else {
            holder.videoTitle.setMaxLines(2);
        }
        holder.videoTitle.setText(video.title);

        if (video.views > 0) {
            String viewsText = Utils.formatNumber(context, video.views);
            if (layoutResource == R.layout.item_video) {
                holder.channelTitle.setText(video.channel + " · " + context.getString(R.string.views, viewsText) + (video.publishedAt == null ? "" : " · " + Utils.formatTimeAgo(context, video.publishedAt)));
            } else if (layoutResource == R.layout.item_video_compact) {
                if (isInChannelActivity) {
                    holder.channelTitle.setText(context.getString(R.string.views, viewsText) + (video.publishedAt == null ? "" : "\n" + Utils.formatTimeAgo(context, video.publishedAt)));
                } else {
                    holder.channelTitle.setText(video.channel + "\n" + viewsText + (video.publishedAt == null ? "" : " · " + Utils.formatTimeAgo(context, video.publishedAt)));
                }
            } else {
                holder.channelTitle.setText(video.channel);
            }
        } else {
            if (layoutResource == R.layout.item_video) {
                holder.channelTitle.setText(video.channel);
            } else if (layoutResource == R.layout.item_video_compact) {
                holder.channelTitle.setText(isInChannelActivity ? "" : video.channel);
            } else {
                holder.channelTitle.setText(video.channel);
            }
        }

        if (holder.duration != null) {
            if (video.duration != null && video.duration.length() > 0) {
                holder.duration.setText(video.duration);
                holder.duration.setVisibility(View.VISIBLE);
            } else {
                holder.duration.setVisibility(View.GONE);
            }
        }

        holder.video = video;
        holder.imagesLoaded = false;
        holder.videoThumb.setImageBitmap(null);
        if (holder.channelThumb != null) {
            holder.channelThumb.setImageBitmap(null);
        }

        // Standard ListViews recycle naturally: load the images immediately.
        if (!isLazyLoading) {
            ImageLoader.loadImage(video.thumbnail, holder.videoThumb);
            loadChannelIcon(holder);
            holder.imagesLoaded = true;
        }

        return row;
    }

    /** Called by VideoActivity when this row becomes visible. */
    public void loadImagesForView(View row) {
        if (row.getTag() instanceof ViewHolder) {
            ViewHolder holder = (ViewHolder) row.getTag();
            if (!holder.imagesLoaded && holder.video != null) {
                ImageLoader.loadImage(holder.video.thumbnail, holder.videoThumb);
                loadChannelIcon(holder);
                holder.imagesLoaded = true;
            }
        }
    }

    /** Re-checks and reloads the channel icon of a row after a fallback
     *  URL was resolved. */
    public void updateChannelIconForView(View row, String resolvedChannelId) {
        if (row != null && row.getTag() instanceof ViewHolder) {
            ViewHolder holder = (ViewHolder) row.getTag();
            if (holder.video != null && holder.imagesLoaded && resolvedChannelId.equals(holder.video.channelId)) {
                loadChannelIcon(holder);
            }
        }
    }

    /** Centralized channel icon loading with the fallback logic. */
    private void loadChannelIcon(final ViewHolder holder) {
        if (holder.channelThumb == null || holder.video == null) return;
        String urlToLoad = holder.video.channelThumbnail;
        if (iconListener != null && holder.video.channelId != null && holder.video.channelId.length() > 0) {
            String resolved = iconListener.getResolvedIcon(holder.video.channelId);
            if ("FAILED".equals(resolved)) {
                return;
            } else if (resolved != null) {
                urlToLoad = resolved;
            }
        }
        if (urlToLoad == null || urlToLoad.length() == 0) {
            if (iconListener != null && holder.video.channelId != null && holder.video.channelId.length() > 0) {
                iconListener.onRequestFallbackIcon(holder.video.channelId);
            }
        } else {
            ImageLoader.loadImage(urlToLoad, holder.channelThumb, true, new ImageLoader.ImageLoaderCallback() {
                @Override
                public void onFail() {
                    if (iconListener != null && holder.video.channelId != null && holder.video.channelId.length() > 0) {
                        String resolved = iconListener.getResolvedIcon(holder.video.channelId);
                        if (resolved == null) {
                            iconListener.onRequestFallbackIcon(holder.video.channelId);
                        }
                    }
                }
            });
        }
    }

    /** Resets the image state so the next visibility check reloads. */
    public void resetImageView(View row) {
        if (row.getTag() instanceof ViewHolder) {
            ViewHolder holder = (ViewHolder) row.getTag();
            holder.imagesLoaded = false;
            if (holder.videoThumb != null) holder.videoThumb.setImageBitmap(null);
            if (holder.channelThumb != null) holder.channelThumb.setImageBitmap(null);
        }
    }

    private static class ViewHolder {
        TextView videoTitle;
        TextView channelTitle;
        TextView duration;
        ImageView videoThumb;
        ImageView channelThumb;
        VideoInfo video;
        boolean imagesLoaded;
    }
}