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

/** Adapter for video lists with lazy loading support: images load only for rows explicitly marked as visible. */
public class VideoAdapter extends ArrayAdapter<VideoInfo> implements ChannelIconRefreshable {
    private Context context;
    private int layoutResource;
    private boolean isInChannelActivity;
    private ChannelIconListener iconListener;
    private io.github.gohoski.notpipe.util.ChannelIconResolver.OnIconResolvedListener iconResolveListener;

    /** Layout flags: which row style getView is rendering. */
    private boolean isFullLayout;
    private boolean isCompactLayout;
    private boolean isNoIconLayout;

    /** Avatar views are small: decode at a fraction of the source size. */
    private static final int AVATAR_MAX_DIM = 80;

    public interface ChannelIconListener {
        String getResolvedIcon(String channelId);
        void onRequestFallbackIcon(String channelId);
    }

    public VideoAdapter(Context context, int resource, List<VideoInfo> objects) {
        this(context, resource, objects, false);
    }

    public VideoAdapter(Context context, int resource, List<VideoInfo> objects, boolean isInChannelActivity) {
        super(context, resource, objects);
        this.context = context;
        this.layoutResource = resource;
        this.isInChannelActivity = isInChannelActivity;
        this.isFullLayout = resource == R.layout.item_video;
        this.isCompactLayout = resource == R.layout.item_video_compact;
        this.isNoIconLayout = resource == R.layout.item_video_noicon;
    }

    public void setChannelIconListener(ChannelIconListener listener) {
        this.iconListener = listener;
    }

    /** Shared resolver listener: rows refresh once a background icon resolution completes (notPipe 0.4 pattern). */
    public void setChannelIconResolveListener(io.github.gohoski.notpipe.util.ChannelIconResolver.OnIconResolvedListener listener) {
        this.iconResolveListener = listener;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final VideoInfo video = getItem(position);

        ViewHolder holder;
        View row = convertView;

        boolean isLazyLoading = parent instanceof AdapterLinearLayout;

        if (row == null || !(row.getTag() instanceof ViewHolder)) {
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

        holder.videoTitle.setMaxLines((isCompactLayout || isNoIconLayout) && (video.views <= 0 || video.publishedAt == null) ? 3 : 2);
        holder.videoTitle.setText(video.title);

        holder.channelTitle.setText(buildMetaText(video));

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
        // Cancel any load a recycled view still has in flight and re-tag the views, so a late decode can never land in the wrong row.
        ImageLoader.cancelLoad(holder.videoThumb);
        holder.videoThumb.setTag(video.thumbnail);
        if (holder.channelThumb != null) {
            ImageLoader.cancelLoad(holder.channelThumb);
        }
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

    /** Builds the secondary text of a row: channel name, view count and publish date. */
    private String buildMetaText(VideoInfo video) {
        String ago = video.publishedAt == null ? null : Utils.formatTimeAgo(context, video.publishedAt);

        if (isCompactLayout || isNoIconLayout) {
            String views = video.views > 0 ? Utils.formatNumber(context, video.views) : null;
            if (isInChannelActivity) {
                return join("\n", views == null ? null : context.getString(R.string.views, views), ago);
            }
            String meta = join(" · ", views, ago);
            return meta.length() > 0 ? video.channel + "\n" + meta : video.channel;
        }

        if (isFullLayout) {
            String views = video.views > 0 ? context.getString(R.string.views, Utils.formatNumber(context, video.views)) : null;
            return join(" · ", video.channel, views, ago);
        }

        return video.channel;
    }

    /** Concatenates non-empty parts, inserting the separator between them. */
    private static String join(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null || parts[i].length() == 0) continue;
            if (sb.length() > 0) sb.append(separator);
            sb.append(parts[i]);
        }
        return sb.toString();
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

    /** Re-checks and reloads the channel icon of a row after a fallback URL was resolved. */
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
        // notPipe 0.4 path: one shared resolver call that prefers the remembered URL, decodes at avatar size and falls back by ID.
        if (iconResolveListener != null) {
            io.github.gohoski.notpipe.util.ChannelIconResolver.loadInto(
                    holder.channelThumb, holder.video.channelId,
                    holder.video.channelThumbnail, iconResolveListener);
            return;
        }
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
                        // getResolvedIcon returns null once a parked failure cooled down, so this retries after the cooldown instead.
                        String resolved = iconListener.getResolvedIcon(holder.video.channelId);
                        if (resolved == null) {
                            iconListener.onRequestFallbackIcon(holder.video.channelId);
                        }
                    }
                }
            }, AVATAR_MAX_DIM);
        }
    }

    /** Resets the image state so the next visibility check reloads (pending loads are cancelled). */
    public void resetImageView(View row) {
        if (row.getTag() instanceof ViewHolder) {
            ViewHolder holder = (ViewHolder) row.getTag();
            if (holder.imagesLoaded) {
                holder.imagesLoaded = false;
                if (holder.videoThumb != null) ImageLoader.unbindImageView(holder.videoThumb);
                if (holder.channelThumb != null) ImageLoader.unbindImageView(holder.channelThumb);
            }
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