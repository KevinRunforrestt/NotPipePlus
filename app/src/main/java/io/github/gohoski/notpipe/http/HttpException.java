package io.github.gohoski.notpipe.http;

/**
 * Created by Gleb on 21.08.2025.
 */

import java.io.IOException;

public class HttpException extends IOException {
    private final String responseBody;

    public HttpException(int statusCode, String message, String responseBody) {
        super(message);
        this.responseBody = responseBody;
    }

    public String getResponseBody() {
        return responseBody;
    }
}