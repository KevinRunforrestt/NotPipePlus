package io.github.gohoski.notpipe.ui;

/**
 * Created by Gleb on 18.01.2026.
 */

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

// This class forces the ImageView to always be 16:9
public class AspectRatioImageView extends ImageView {

    public AspectRatioImageView(Context context) {
        super(context);
    }

    public AspectRatioImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (int) (width * (9.0 / 16.0));
        setMeasuredDimension(width, height);
    }
}