package io.github.gohoski.notpipe.util;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cc.nnproject.json.JSONArray;
import cc.nnproject.json.JSONObject;

/**
 * Universal subscriptions import: accepts the Google Takeout CSV
 * ("Channel ID,Channel Url,Channel Title"), the NewPipe CSV export
 * (feed_url,upload_url,channel_id,channel_name), this app's own export,
 * a NewPipe JSON export, or any text listing channel IDs — and returns
 * subscription entries no matter which of those shapes the file has.
 *
 * Robustness, because one single format broke the old import badly:
 *  - charset: BOM detection for UTF-8 / UTF-16LE / UTF-16BE (Windows
 *    Notepad "Unicode" files), strict UTF-8 with ISO-8859-1 fallback;
 *  - separator: comma, semicolon (Excel in Spanish locales!) or tab,
 *    auto-detected per file;
 *  - quoted fields: proper CSV splitting that honors "a,b" quoting;
 *  - one bad row never kills the rest (per-row try/catch);
 *  - when structured parsing yields nothing, a regex scan of the whole
 *    file still finds every channel ID and youtube.com/@handle URL.
 *
 * Java 1.5 (Android 1.5+).
 */
public final class SubscriptionsImporter {

    /** Channel id: "UC" + exactly 22 of [A-Za-z0-9_-]. */
    private static final Pattern UC_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");
    /** @handle inside a youtube.com URL ("youtube.com/@name/..."). */
    private static final Pattern URL_HANDLE =
            Pattern.compile("youtube\\.com/@([A-Za-z0-9_.\\-]{3,30})");
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private SubscriptionsImporter() {}

    /**
     * Parses raw file bytes into subscription entries {id, name, ""}.
     * Never returns null; an empty list means nothing was recognized.
     */
    public static List<String[]> parse(byte[] data) {
        List<String[]> out = new ArrayList<String[]>();
        if (data == null || data.length == 0) return out;
        String text = decode(data);
        if (text == null || text.trim().length() == 0) return out;

        HashSet seen = new HashSet();
        // 1. Structured CSV rows (id + display name when available).
        try {
            parseRows(text, out, seen);
        } catch (Throwable ignored) {
            // A totally malformed file must still reach the regex scan.
        }
        // 2. Regex safety net: any channel id / handle URL anywhere in
        //    the file (covers JSON exports, exotic CSV shapes, ...).
        try {
            scanIds(text, out, seen);
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /** BOM-aware decode: UTF-8 (strict, then ISO-8859-1) and UTF-16 LE/BE. */
    private static String decode(byte[] data) {
        try {
            if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                    && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
                return decodeStrict(data, 3, "UTF-8", true);
            }
            if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
                return decodeStrict(data, 2, "UTF-16LE", false);
            }
            if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
                return decodeStrict(data, 2, "UTF-16BE", false);
            }
            // No BOM: heuristics. Many NUL bytes -> UTF-16 without BOM.
            int nulls = 0;
            int probe = Math.min(data.length, 1024);
            for (int i = 0; i < probe; i++) {
                if (data[i] == 0) nulls++;
            }
            if (probe > 32 && nulls * 4 > probe) {
                // Latin text in UTF-16LE has NULs on odd positions.
                boolean odd = true;
                for (int i = 0; i < probe; i++) {
                    if (data[i] == 0) {
                        odd = (i % 2) == 1;
                        break;
                    }
                }
                return decodeStrict(data, 0, odd ? "UTF-16LE" : "UTF-16BE", false);
            }
            return decodeStrict(data, 0, "UTF-8", true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decodeStrict(byte[] data, int skip, String charset,
            boolean latinFallback) {
        try {
            CharsetDecoder dec = Charset.forName(charset).newDecoder();
            dec.onMalformedInput(CodingErrorAction.REPORT);
            dec.onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(java.nio.ByteBuffer.wrap(data, skip, data.length - skip)).toString();
        } catch (Throwable t) {
            if (latinFallback) {
                try {
                    return new String(data, skip, data.length - skip, "ISO-8859-1");
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Structured CSV parsing
    // ------------------------------------------------------------------

    private static void parseRows(String text, List<String[]> out, HashSet seen) {
        String[] lines = splitLines(text);
        // Separator detection: count commas / semicolons / tabs over the
        // first non-empty lines (Excel-Spanish writes ";" separated CSV).
        char sep = detectSeparator(lines);
        if (sep == 0) sep = ',';
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            try {
                List fields = splitCsvLine(line, sep);
                if (fields.isEmpty()) continue;
                String id = null;
                String name = null;
                for (int f = 0; f < fields.size(); f++) {
                    String p = ((String) fields.get(f)).trim();
                    if (p.length() == 0) continue;
                    if (isChannelId(p) && id == null) {
                        id = p;
                    } else if (id == null && p.charAt(0) == '@' && p.length() > 1
                            && !p.startsWith("@@")) {
                        id = p;
                    }
                }
                // Display name: the last field that is not a URL, not the
                // id and not a pure number (Takeout col 3, NewPipe col 4).
                for (int f = fields.size() - 1; f >= 0 && name == null; f--) {
                    String p = ((String) fields.get(f)).trim();
                    if (p.length() == 0) continue;
                    if (p.startsWith("http") || p.startsWith("www.")) continue;
                    if (isChannelId(p) || p.equals(id)) continue;
                    boolean onlyDigits = true;
                    for (int c = 0; c < p.length(); c++) {
                        char ch = p.charAt(c);
                        if (!Character.isDigit(ch)) {
                            onlyDigits = false;
                            break;
                        }
                    }
                    if (onlyDigits) continue;
                    name = p;
                }
                if (id != null && !seen.contains(id)) {
                    seen.add(id);
                    out.add(new String[]{ id, name == null ? "" : name, "" });
                }
            } catch (Throwable ignored) {
                // One broken row must not kill the file.
            }
        }
    }

    private static boolean isChannelId(String s) {
        if (s.length() != 24 || s.charAt(0) != 'U' || s.charAt(1) != 'C') return false;
        for (int i = 2; i < 24; i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static char detectSeparator(String[] lines) {
        int commas = 0, semis = 0, tabs = 0;
        int counted = 0;
        for (int i = 0; i < lines.length && counted < 20; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            counted++;
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == ',') commas++;
                else if (ch == ';') semis++;
                else if (ch == '\t') tabs++;
            }
        }
        if (semis > commas && semis >= tabs) return ';';
        if (tabs > commas && tabs > semis) return '\t';
        if (commas > 0) return ',';
        return 0;
    }

    /** Split on \n, \r\n and lone \r. */
    private static String[] splitLines(String text) {
        List lines = new ArrayList();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
        }
        lines.add(text.substring(start));
        return (String[]) lines.toArray(new String[lines.size()]);
    }

    /** CSV field split honoring double-quoted fields with embedded separators. */
    private static List splitCsvLine(String line, char sep) {
        List fields = new ArrayList();
        StringBuffer cur = new StringBuffer();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == sep) {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    // ------------------------------------------------------------------
    // Regex safety net (also covers JSON exports)
    // ------------------------------------------------------------------

    private static void scanIds(String text, List<String[]> out, HashSet seen) {
        // Try JSON first: a NewPipe export carries clean name/id pairs.
        if (looksLikeJson(text)) {
            try {
                JSONArray arr = null;
                String trimmed = text.trim();
                if (trimmed.charAt(0) == '[') {
                    arr = new JSONArray(trimmed);
                } else {
                    JSONObject obj = new JSONObject(trimmed);
                    arr = obj.getArray("subscriptions");
                    if (arr == null) arr = obj.getArray("channels");
                }
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        try {
                            JSONObject o = arr.getObject(i);
                            String url = o.getString("url", "");
                            String id = extractId(url);
                            if (id == null) id = o.getString("id", "");
                            if (id == null || id.length() == 0 || seen.contains(id)) continue;
                            String name = o.getString("name", "");
                            if (name == null || name.length() == 0) name = "";
                            seen.add(id);
                            out.add(new String[]{ id, name, "" });
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Plain regex sweep over everything: channel ids...
        Matcher m = UC_ID.matcher(text);
        while (m.find()) {
            String id = m.group();
            if (!seen.contains(id)) {
                seen.add(id);
                out.add(new String[]{ id, "", "" });
            }
        }
        // ...and youtube.com/@handle URLs (they resolve lazily later).
        m = URL_HANDLE.matcher(text);
        while (m.find()) {
            String handle = "@" + m.group(1);
            if (!seen.contains(handle)) {
                seen.add(handle);
                out.add(new String[]{ handle, "", "" });
            }
        }
    }

    private static boolean looksLikeJson(String text) {
        String t = text.trim();
        return t.length() > 0 && (t.charAt(0) == '{' || t.charAt(0) == '[');
    }

    private static String extractId(String url) {
        if (url == null) return null;
        int idx = url.indexOf("/channel/");
        if (idx != -1) {
            String rest = url.substring(idx + 9);
            int end = rest.indexOf('/');
            if (end != -1) rest = rest.substring(0, end);
            rest = rest.trim();
            if (isChannelId(rest)) return rest;
        }
        return null;
    }
}
