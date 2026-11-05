package io.github.gohoski.notpipe.api;

import java.io.IOException;

/** Created by Gleb on 17.06.2026. */
public class ContentUnavailableException extends IOException {
    public ContentUnavailableException(String message) {
        super(message);
    }
}
