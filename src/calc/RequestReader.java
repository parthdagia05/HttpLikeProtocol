package calc;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads one HTTP/1.x request at a time from a persistent connection.
 *
 * The whole point: a request ends exactly where its framing says it ends. Headers end at the
 * first empty line; the body is exactly Content-Length bytes (or the chunked encoding's last
 * chunk). Nothing past that is consumed, because byte n+1 is the start of the next request.
 */
final class RequestReader {
    static final int MAX_LINE = 8 * 1024;
    static final int MAX_HEADERS = 100;
    static final int MAX_BODY = 1024 * 1024;

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern VERSION = Pattern.compile("HTTP/\\d\\.\\d");

    record Request(String method, String target, String version,
                   Map<String, List<String>> headers, byte[] body) {
        /** All values of a header, in order. Names are lowercase. */
        List<String> all(String name) {
            return headers.getOrDefault(name, List.of());
        }

        String first(String name) {
            List<String> v = all(name);
            return v.isEmpty() ? null : v.get(0);
        }
    }

    /** The request can't be framed, so the connection can't be trusted after the reply. */
    static final class BadRequest extends Exception {
        final int status;

        BadRequest(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    private final InputStream in;

    RequestReader(InputStream in) {
        this.in = in;
    }

    /** Returns the next request, or null if the client closed cleanly between requests. */
    Request read() throws IOException, BadRequest {
        String line = readLine(true, 414, "request line too long");
        if (line == null) return null;
        if (line.isEmpty()) {                      // tolerate one stray CRLF between requests
            line = readLine(false, 414, "request line too long");
        }

        String[] parts = line.split(" ", -1);
        if (parts.length != 3 || !TOKEN.matcher(parts[0]).matches() || parts[1].isEmpty()) {
            throw new BadRequest(400, "malformed request line");
        }
        String version = parts[2];
        if (!VERSION.matcher(version).matches()) throw new BadRequest(400, "malformed HTTP version");
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new BadRequest(505, "only HTTP/1.0 and HTTP/1.1 are supported");
        }

        Map<String, List<String>> headers = readHeaders();
        byte[] body = readBody(headers);
        return new Request(parts[0], parts[1], version, headers, body);
    }

    private Map<String, List<String>> readHeaders() throws IOException, BadRequest {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int count = 0;
        while (true) {
            String line = readLine(false, 431, "header line too long");
            if (line.isEmpty()) return headers;
            if (++count > MAX_HEADERS) throw new BadRequest(431, "too many headers");
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new BadRequest(400, "obsolete header folding");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) throw new BadRequest(400, "header without a colon");
            String name = line.substring(0, colon);
            if (!TOKEN.matcher(name).matches()) throw new BadRequest(400, "bad header name");
            String value = line.substring(colon + 1).strip();
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
        }
    }

    private byte[] readBody(Map<String, List<String>> headers) throws IOException, BadRequest {
        List<String> te = headers.getOrDefault("transfer-encoding", List.of());
        List<String> cl = headers.getOrDefault("content-length", List.of());
        if (!te.isEmpty() && !cl.isEmpty()) {
            // Two framings that could disagree: the classic request smuggling setup.
            throw new BadRequest(400, "both Transfer-Encoding and Content-Length");
        }
        if (!te.isEmpty()) {
            String coding = String.join(",", te).strip().toLowerCase(Locale.ROOT);
            if (!coding.equals("chunked")) throw new BadRequest(501, "unsupported transfer coding: " + coding);
            return readChunked();
        }
        if (!cl.isEmpty()) {
            long length = parseContentLength(cl);
            if (length > MAX_BODY) throw new BadRequest(413, "body larger than " + MAX_BODY + " bytes");
            return readExactly((int) length);
        }
        return new byte[0];
    }

    /** Every Content-Length value (repeated or comma-joined) must be the same decimal number. */
    private static long parseContentLength(List<String> values) throws BadRequest {
        long length = -1;
        for (String v : values) {
            for (String piece : v.split(",")) {
                String p = piece.strip();
                if (!p.matches("\\d{1,18}")) throw new BadRequest(400, "bad Content-Length");
                long n = Long.parseLong(p);
                if (length >= 0 && n != length) throw new BadRequest(400, "conflicting Content-Length");
                length = n;
            }
        }
        return length;
    }

    private byte[] readChunked() throws IOException, BadRequest {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(false, 400, "chunk size line too long");
            int semi = line.indexOf(';');                      // chunk extensions are ignored
            String hex = (semi < 0 ? line : line.substring(0, semi)).strip();
            if (!hex.matches("[0-9A-Fa-f]{1,7}")) throw new BadRequest(400, "bad chunk size");
            int size = Integer.parseInt(hex, 16);
            if (size == 0) break;
            if (body.size() + size > MAX_BODY) throw new BadRequest(413, "body larger than " + MAX_BODY + " bytes");
            body.write(readExactly(size));
            if (!readLine(false, 400, "missing CRLF after chunk").isEmpty()) {
                throw new BadRequest(400, "chunk longer than its size");
            }
        }
        // Trailer section: header lines until the final empty line. Their values are ignored.
        int trailers = 0;
        while (!readLine(false, 431, "trailer line too long").isEmpty()) {
            if (++trailers > MAX_HEADERS) throw new BadRequest(431, "too many trailers");
        }
        return body.toByteArray();
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] b = in.readNBytes(n);
        if (b.length != n) throw new EOFException("client closed mid-body");
        return b;
    }

    /**
     * Reads up to LF and strips an optional CR. Returns null on EOF before the first byte only
     * when {@code eofOk}; EOF anywhere else is a truncated request.
     */
    private String readLine(boolean eofOk, int tooLongStatus, String tooLongMsg) throws IOException, BadRequest {
        byte[] buf = new byte[256];
        int len = 0;
        while (true) {
            int c = in.read();
            if (c < 0) {
                if (eofOk && len == 0) return null;
                throw new EOFException("client closed mid-request");
            }
            if (c == '\n') break;
            if (len == MAX_LINE) throw new BadRequest(tooLongStatus, tooLongMsg);
            if (len == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[len++] = (byte) c;
        }
        if (len > 0 && buf[len - 1] == '\r') len--;
        return new String(buf, 0, len, StandardCharsets.ISO_8859_1);
    }
}
