package io.github.gohoski.notpipe.api;

/** Created by Gleb on 02.11.2026. */
public enum Codec {
    /** MPEG-4 Visual, 270p */
    MPEG4,
    /** MPEG-4 Visual, 360p */
    MPEG4_360,
    /** H.263, 288p */
    H263;

    /** Resolves a persisted codec index to a Codec. */
    public static Codec fromIndex(int index) {
        Codec[] values = values();
        if (index < 0 || index >= values.length) {
            return MPEG4;
        }
        return values[index];
    }
}
