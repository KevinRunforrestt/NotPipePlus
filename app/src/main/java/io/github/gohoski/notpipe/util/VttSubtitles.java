package io.github.gohoski.notpipe.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal WebVTT subtitle parser + cue lookup, designed for old devices:
 *  - one linear array of cues, parsed once in the background
 *  - getTextAt() is a binary search over plain long[] arrays, so the
 *    playback tick handler costs only a few comparisons per 300 ms
 *  - no allocations while ticking: it returns the pre-built cue text
 *
 * Supports what the Invidious/Piped caption endpoints actually emit:
 * WEBVTT header, NOTE/STYLE blocks, "HH:MM:SS.mmm --> HH:MM:SS.mmm"
 * cue lines (with optional trailing settings), multi-line cue text,
 * inline tags (&lt;c&gt;, &lt;v Speaker&gt;, timestamps) and basic entities.
 */
public class VttSubtitles {

    private static final Pattern CUE = Pattern.compile(
            "(\\d{1,3}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*(\\d{1,3}):(\\d{2}):(\\d{2})[.,](\\d{3}).*");

    private final long[] starts;
    private final long[] ends;
    private final String[] texts;

    private VttSubtitles(long[] starts, long[] ends, String[] texts) {
        this.starts = starts;
        this.ends = ends;
        this.texts = texts;
    }

    /** Number of parsed cues; 0 means "nothing to show". */
    public int size() {
        return starts.length;
    }

    /**
     * Returns the cue text valid at the given player position (ms),
     * or null when no cue is active. Allocation-free.
     */
    public String getTextAt(long positionMs) {
        int lo = 0, hi = starts.length - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] > positionMs) {
                hi = mid - 1;
            } else if (ends[mid] < positionMs) {
                lo = mid + 1;
            } else {
                found = mid;
                break;
            }
        }
        // Cues are sorted by start time; overlapping cues are extremely rare
        // in caption feeds, so the first hit is good enough.
        if (found != -1) return texts[found];
        return null;
    }

    /**
     * Parses WebVTT text. Returns an empty (size 0) object instead of
     * throwing when the payload does not look like WebVTT, so callers
     * can simply check size().
     */
    public static VttSubtitles parse(String vtt) {
        if (vtt == null) return new VttSubtitles(new long[0], new long[0], new String[0]);
        // Strip BOM and normalize line endings once.
        String s = vtt;
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        s = s.replace('\r', '\n');

        List<long[]> times = new ArrayList<long[]>();
        List<String> texts = new ArrayList<String>();

        int i = 0;
        int len = s.length();
        // The first line should be "WEBVTT"; tolerate leading blank lines
        // and missing headers (YouTube's timedtext is well-formed anyway).
        while (i < len) {
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd == -1) lineEnd = len;
            String line = s.substring(i, lineEnd);
            i = lineEnd + 1;

            if (line.length() == 0) continue;
            // Skip blocks that are not cues.
            if (line.startsWith("WEBVTT") || line.startsWith("NOTE")
                    || line.startsWith("STYLE") || line.startsWith("REGION")
                    || line.startsWith("Kind:") || line.startsWith("Language:")) {
                // Skip to the end of this block (blank line).
                while (i < len) {
                    lineEnd = s.indexOf('\n', i);
                    if (lineEnd == -1) lineEnd = len;
                    String l = s.substring(i, lineEnd);
                    i = lineEnd + 1;
                    if (l.length() == 0) break;
                }
                continue;
            }

            Matcher m = CUE.matcher(line);
            if (m.matches()) {
                long start = time(m, 1);
                long end = time(m, 5);
                // Collect cue text lines until a blank line.
                StringBuilder text = new StringBuilder();
                while (i < len) {
                    lineEnd = s.indexOf('\n', i);
                    if (lineEnd == -1) lineEnd = len;
                    String l = s.substring(i, lineEnd);
                    i = lineEnd + 1;
                    if (l.length() == 0) break;
                    if (text.length() > 0) text.append('\n');
                    text.append(cleanLine(l));
                }
                String t = text.toString().trim();
                if (t.length() > 0 && end > start) {
                    times.add(new long[]{start, end});
                    texts.add(t);
                }
            } else {
                // Unrecognized line (cue identifier etc.): it is followed by
                // the real cue line, so just continue scanning.
            }
        }

        long[] startsArr = new long[times.size()];
        long[] endsArr = new long[times.size()];
        String[] textsArr = new String[times.size()];
        for (int k = 0; k < times.size(); k++) {
            startsArr[k] = times.get(k)[0];
            endsArr[k] = times.get(k)[1];
            textsArr[k] = texts.get(k);
        }
        return new VttSubtitles(startsArr, endsArr, textsArr);
    }

    /** Extracts HH:MM:SS.mmm from a matcher starting at the given group. */
    private static long time(Matcher m, int g) {
        try {
            long h = Long.parseLong(m.group(g));
            long min = Long.parseLong(m.group(g + 1));
            long sec = Long.parseLong(m.group(g + 2));
            long ms = Long.parseLong(m.group(g + 3));
            return ((h * 60 + min) * 60 + sec) * 1000 + ms;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Strips inline tags and decodes the basic entities of one text line. */
    private static String cleanLine(String l) {
        if (l.indexOf('<') != -1) {
            StringBuilder sb = new StringBuilder(l.length());
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                if (c == '<') {
                    int close = l.indexOf('>', i);
                    if (close == -1) break;
                    i = close;
                } else {
                    sb.append(c);
                }
            }
            l = sb.toString();
        }
        if (l.indexOf('&') != -1) {
            l = replace(l, "&amp;", "&");
            l = replace(l, "&lt;", "<");
            l = replace(l, "&gt;", ">");
            l = replace(l, "&quot;", "\"");
            l = replace(l, "&apos;", "'");
            l = replace(l, "&#39;", "'");
            l = replace(l, "&nbsp;", " ");
        }
        return l;
    }

    private static String replace(String src, String target, String replacement) {
        if (src == null || target == null || target.length() == 0) return src;
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
