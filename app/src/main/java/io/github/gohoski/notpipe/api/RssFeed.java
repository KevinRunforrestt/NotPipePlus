package io.github.gohoski.notpipe.api;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import io.github.gohoski.notpipe.data.VideoInfo;
import io.github.gohoski.notpipe.http.HttpClient;
import io.github.gohoski.notpipe.http.HttpRequest;

/**
 * NewPipe-style channel RSS route: every Invidious instance serves a tiny
 * Atom feed of a channel's latest uploads at /feed/channel/{id}. It is the
 * cheapest always-fresh source for the subscriptions feed — one small
 * document with real publish dates and view counts, no JSON extraction of
 * the heavy channel page.
 *
 * The parser is string-based on purpose (no javax.xml on Android 1.5 and
 * allocation-light scanning for old devices). Java 1.5 compatible.
 */
public final class RssFeed {

    private static final int MAX_ENTRIES = 30;

    private RssFeed() {}

    /**
     * Fetches and parses the latest videos of one channel.
     *
     * @param base Invidious instance base URL (http://host[:port])
     * @param channelId UC id of the channel
     * @return the parsed videos; null when the route did not deliver a
     *         usable feed (the caller rotates to another instance)
     */
    public static List<VideoInfo> fetch(String base, String channelId) throws IOException {
        if (base == null || channelId == null) return null;
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        HttpRequest req = new HttpRequest(b + "/feed/channel/" + channelId);
        String xml = HttpClient.executeToString(req);
        return parse(xml, b, channelId);
    }

    /** Parses the Atom payload; null when it does not look like the
     *  Invidious channel feed (bot-challenge HTML, error XML, ...). */
    static List<VideoInfo> parse(String xml, String base, String channelId) {
        if (xml == null) return null;
        String s = xml.trim();
        if (s.length() == 0) return null;
        // A bot-challenge page is HTML, not a feed.
        if (s.startsWith("<!DOCTYPE") || s.startsWith("<html") || s.startsWith("<")) {
            if (s.indexOf("<feed") == -1 && s.indexOf("<entry") == -1) return null;
        }
        if (s.indexOf("<error") != -1 && s.indexOf("<entry") == -1) return null;

        // Channel name from the feed header (entries repeat it anyway).
        String channelName = textBetween(s, "<author>", "</author>");
        if (channelName != null) {
            int n = channelName.indexOf("<name>");
            if (n != -1) {
                int e = channelName.indexOf("</name>", n);
                channelName = e == -1 ? null : decode(channelName.substring(n + 6, e).trim());
            }
        }
        if (channelName == null || channelName.length() == 0) channelName = "";

        List<VideoInfo> videos = new ArrayList<VideoInfo>();
        int pos = 0;
        while (videos.size() < MAX_ENTRIES) {
            int entryStart = s.indexOf("<entry>", pos);
            if (entryStart == -1) break;
            int entryEnd = s.indexOf("</entry>", entryStart);
            if (entryEnd == -1) break;
            String entry = s.substring(entryStart, entryEnd);
            pos = entryEnd + 8;

            String id = textBetween(entry, "<yt:videoId>", "</yt:videoId>");
            if (id == null || id.length() != 11) continue;
            String title = textBetween(entry, "<title>", "</title>");
            if (title == null || title.length() == 0) continue;
            long views = -1L;
            String stats = textBetween(entry, "<media:statistics", "/>");
            if (stats != null) {
                String v = attr(stats, "views");
                if (v != null) {
                    try {
                        views = Long.parseLong(v);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            Date published = null;
            String pub = textBetween(entry, "<published>", "</published>");
            if (pub != null) published = parseIso(pub);

            String thumb = base + "/vi/" + id + "/mqdefault.jpg";
            String author = channelName;
            String entryAuthor = textBetween(entry, "<author>", "</author>");
            if (entryAuthor != null) {
                int n = entryAuthor.indexOf("<name>");
                if (n != -1) {
                    int e = entryAuthor.indexOf("</name>", n);
                    if (e != -1) {
                        String name = decode(entryAuthor.substring(n + 6, e).trim());
                        if (name.length() > 0) author = name;
                    }
                }
            }

            videos.add(new VideoInfo(id, decode(title), thumb, author, null,
                    channelId, "", views, published));
        }
        if (videos.isEmpty()) return null;
        return videos;
    }

    /** Text between two literal markers, or null. */
    private static String textBetween(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a == -1) return null;
        int b = s.indexOf(close, a + open.length());
        if (b == -1) return null;
        return s.substring(a + open.length(), b);
    }

    /** value="..." of one attribute inside a tag fragment. */
    private static String attr(String tag, String name) {
        int i = tag.indexOf(name + "=\"");
        if (i == -1) return null;
        int start = i + name.length() + 2;
        int end = tag.indexOf('"', start);
        if (end == -1) return null;
        return tag.substring(start, end);
    }

    /** ISO-8601 with timezone ("2026-10-27T13:30:51+00:00"). */
    private static Date parseIso(String iso) {
        if (iso == null || iso.length() < 19) return null;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            long ms = sdf.parse(iso.substring(0, 19)).getTime();
            // Manual timezone offset ("+HH:MM" / "-HH:MM").
            if (iso.length() >= 25) {
                try {
                    char sign = iso.charAt(19);
                    int oh = Integer.parseInt(iso.substring(20, 22));
                    int om = Integer.parseInt(iso.substring(23, 25));
                    long offset = (oh * 60L + om) * 60000L;
                    if (sign == '+') ms -= offset;
                    else if (sign == '-') ms += offset;
                } catch (Exception ignored) {
                }
            }
            return new Date(ms);
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes the basic XML entities of RSS text. */
    private static String decode(String s) {
        if (s == null || s.indexOf('&') == -1) return s;
        s = replace(s, "&amp;", "&");
        s = replace(s, "&lt;", "<");
        s = replace(s, "&gt;", ">");
        s = replace(s, "&quot;", "\"");
        s = replace(s, "&apos;", "'");
        s = replace(s, "&#39;", "'");
        return s;
    }

    private static String replace(String src, String target, String replacement) {
        int idx = src.indexOf(target);
        if (idx == -1) return src;
        StringBuilder sb = new StringBuilder(src.length());
        int prev = 0;
        while (idx != -1) {
            sb.append(src, prev, idx).append(replacement);
            prev = idx + target.length();
            idx = src.indexOf(target, prev);
        }
        sb.append(src, prev, src.length());
        return sb.toString();
    }
}
