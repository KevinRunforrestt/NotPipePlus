package io.github.gohoski.notpipe.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

import io.github.gohoski.notpipe.NotPipe;

/**
 * Created by Gleb on 18.01.2026.
 * VideoView that can be forced 16:9 or fullscreen with actual video aspect ratio.
 */
public class AspectRatioVideoView extends VideoView {

    private boolean fullscreen = false;
    private int videoWidth = 0;
    private int videoHeight = 0;

    public AspectRatioVideoView(Context context) {
        super(context);
    }

    public AspectRatioVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
        requestLayout();
    }

    public void setVideoDimensions(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (width <= 0 || height <= 0) {
            width = getWidth() > 0 ? getWidth() : 320;
            height = getHeight() > 0 ? getHeight() : 240;
        }

        // Apply a strict 16:9 bounding box in non-fullscreen mode
        if (!fullscreen) {
            height = (int) (width * (9.0 / 16.0));
            if (height <= 0) height = (int)(width * 0.5625);
        }

        // Adjust measurements to fit the exact video aspect ratio inside the bounding box
        if (videoWidth > 0 && videoHeight > 0) {
            float videoAspect = (float) videoWidth / videoHeight;
            float screenAspect = (float) width / height;

            if (screenAspect > videoAspect) {
                int measuredWidth = (int) (height * videoAspect);
                setMeasuredDimension(measuredWidth, height);
            } else {
                int measuredHeight = (int) (width / videoAspect);
                setMeasuredDimension(width, measuredHeight);
            }
        } else {
            setMeasuredDimension(width, height);
        }

        if (NotPipe.SDK < 8) {
            postInvalidate();
        }
    }

    public void forceLayoutUpdate() {
        requestLayout();
        invalidate();
        if (NotPipe.SDK < 8) {
            post(new Runnable() {
                @Override
                public void run() {
                    requestLayout();
                    invalidate();
                }
            });
        }
    }
}