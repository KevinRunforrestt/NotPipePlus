package io.github.gohoski.notpipe.api;

import java.io.IOException;

/** Created by Gleb on 16.05.2026. */

public interface Conversion {

    String getConvUrl(String id, Codec codec) throws IOException;
}
