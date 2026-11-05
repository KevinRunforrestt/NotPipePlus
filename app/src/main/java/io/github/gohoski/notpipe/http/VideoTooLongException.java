package io.github.gohoski.notpipe.http;

import java.io.IOException;

/** Created by Gleb on 08.03.2026. */
public class VideoTooLongException extends IOException {
    public VideoTooLongException(String message) {
        super(message);
    }
}
