package io.github.gohoski.notpipe.http;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import io.github.gohoski.notpipe.BuildConfig;
import io.github.gohoski.notpipe.NotPipe;
import io.github.gohoski.notpipe.Utils;

/**
 * Minimal HTTP client for Http(s)URLConnection with timeouts, a shared
 * User-Agent and error bodies surfaced as HttpException.
 */
public class HttpClient {
    public static final String USER_AGENT = "notPipe/" + BuildConfig.VERSION_NAME + " (https://github.com/gohoski/notPipe)";
    private static final int TIMEOUT = 20000;
    public static final int VIDEO_TIMEOUT = 60000;
    public static final int CONVERSION_TIMEOUT = 300000;

    public interface DownloadProgressListener {
        /**
         * Invoked to publish downloaded bytes progression.
         * @return true to continue downloading, false to abort and clean up.
         */
        boolean onProgress(long bytesDownloaded, long totalBytes);
    }

    /** Executes an HTTP request with a custom timeout.
     *  @return InputStream of a 2xx response; HttpException carries the
     *          status line and the error body otherwise. */
    @SuppressWarnings("ConstantConditions")
    public static InputStream execute(HttpRequest request, int timeout) throws IOException {
        Utils.waitForConnection(NotPipe.getAppContext());
        HttpURLConnection connection = null;
        try {
            URL url = new URL(request.getBaseUrl() + request.getEndpoint());
            String urlStr = url.toString().toLowerCase();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(request.getMethod());
            connection.setConnectTimeout(9999);
            connection.setReadTimeout(timeout);

            connection.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            // Ask for gzip (API 3-era HttpURLConnection does not add it
            // by itself): JSON shrinks several-fold, which is most of the
            // wait on slow links. Servers that cannot compress simply
            // ignore the header and the body stays plain.
            if (!request.getHeaders().containsKey("Accept-Encoding")) {
                connection.setRequestProperty("Accept-Encoding", "gzip");
            }

            if (request.getBody() != null && request.getBody().length() > 0) {
                connection.setDoOutput(true);
                if (!request.getHeaders().containsKey("Content-Type"))
                    connection.setRequestProperty("Content-Type", "application/json");
                byte[] postData = request.getBody().getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(postData.length);
                OutputStream os = connection.getOutputStream();
                os.write(postData);
                os.flush(); os.close();
            }

            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                String contentType = connection.getContentType();
                if (urlStr.contains("&codec=") && contentType != null && contentType.startsWith("application/json")) {
                    connection.disconnect();
                    throw new VideoTooLongException("Video is too long!");
                }
                InputStream body = connection.getInputStream();
                // Transparent gzip: wrap only when the server actually
                // compressed the body, plain responses pass through as-is.
                if (isGzipEncoded(connection)) {
                    body = new GZIPInputStream(body);
                }
                return new ConnectionInputStream(body, connection);
            } else {
                InputStream errorStream = connection.getErrorStream();
                String errorBody = streamToString(errorStream);
                connection.disconnect();
                throw new HttpException(status, "HTTP " + status + " " + url, errorBody);
            }
        } catch (IOException e) {
            if (e instanceof HttpException) {
                throw e;
            }
            if (connection != null) {
                connection.disconnect();
            }
            throw e;
        }
    }

    /**
     * Executes the API request and returns the response body as a String
     *
     * @param request The API request to execute
     * @return The response body as a String
     * @throws HttpException If there's an error executing the request
     */
    @SuppressWarnings("ConstantConditions")
    public static String executeToString(HttpRequest request) throws IOException {
        return executeToString(request, TIMEOUT);
    }

    /**
     * Executes the API request with custom timeout and returns the response
     * body as a String. A 4xx/5xx with a structured JSON error payload is
     * returned as a normal String so the API classes can read "error".
     */
    @SuppressWarnings("ConstantConditions")
    public static String executeToString(HttpRequest request, int timeout) throws IOException {
        InputStream in = null;
        try {
            in = execute(request, timeout);
            return streamToString(in);
        } catch (HttpException e) {
            // A structured JSON error payload becomes the normal body so
            // the API classes can extract "error" / "message".
            String body = e.getResponseBody();
            if (body != null) body = body.trim();
            if (body != null && body.startsWith("{") && body.endsWith("}")) {
                return body;
            }
            throw e;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * GETs a URL and returns the redirect target from the Location header
     * (yt2009's video endpoints work this way). 404 means the quality is
     * not available on that instance.
     */
    public static String getRedirectUrl(String baseUrl, String urlString, int timeout) throws IOException {
        Utils.waitForConnection(NotPipe.getAppContext());
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status == 404) {
                throw new FileNotFoundException("Video quality not available (HTTP 404)");
            }
            String loc = connection.getHeaderField("Location");
            if (loc == null)
                throw new IOException("No video url returned");
            else
                return baseUrl + loc;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** True when the response carries a gzip Content-Encoding. */
    private static boolean isGzipEncoded(HttpURLConnection connection) {
        String encoding = connection.getContentEncoding();
        return encoding != null && encoding.toLowerCase().indexOf("gzip") != -1;
    }

    /**
     * Helper method to convert InputStream to String
     */
    private static String streamToString(InputStream is) throws IOException {
        if (is == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        // 8192 halves the read syscall count of the old 1024 buffer.
        byte[] data = new byte[8192];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return new String(buffer.toByteArray(), "UTF-8");
    }

    /**
     * Downloads a file to the given path, reporting progress. If the
     * download fails, the partially downloaded file is deleted.
     */
    public static void downloadToFile(String urlString, String outputPath, int timeout, DownloadProgressListener listener) throws IOException {
        HttpRequest request = new HttpRequest(urlString, "");
        InputStream in = null;
        java.io.FileOutputStream out = null;
        java.io.File file = new java.io.File(outputPath);
        boolean success = false;
        try {
            in = execute(request, timeout);
            // Content length may be -1 for chunked transfers
            long totalBytes = -1;
            if (in instanceof ConnectionInputStream) {
                totalBytes = ((ConnectionInputStream) in).getContentLength();
            }
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new java.io.FileOutputStream(file);
            byte[] buffer = new byte[8192];
            int bytesRead;
            long bytesDownloaded = 0;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                bytesDownloaded += bytesRead;
                if (listener != null) {
                    if (!listener.onProgress(bytesDownloaded, totalBytes)) {
                        throw new IOException("Download aborted by client request.");
                    }
                }
            }
            out.flush();
            success = true;
        } finally {
            if (in != null)
                try { in.close(); } catch (IOException ignored) {}
            if (out != null)
                try { out.close(); } catch (IOException ignored) {}
            if (!success && file.exists()) file.delete();
        }
    }
}