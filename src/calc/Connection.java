package calc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One client connection. Requests are read and answered strictly in order until the client
 * closes, asks for Connection: close, sends something unframeable, or goes idle.
 *
 * Pipelining needs nothing special: requests the client sent ahead simply wait in the input
 * buffer, and each is answered in turn. Output is flushed only when no further request is
 * already buffered, so a pipelined batch goes back in as few packets as possible.
 */
final class Connection implements Runnable {
    private static final Map<Integer, String> REASONS = Map.of(
            200, "OK",
            400, "Bad Request",
            404, "Not Found",
            405, "Method Not Allowed",
            413, "Content Too Large",
            414, "URI Too Long",
            431, "Request Header Fields Too Large",
            501, "Not Implemented",
            505, "HTTP Version Not Supported");

    private record Response(int status, String body, boolean headOnly) {}

    private final Socket socket;
    private final int idleTimeoutSeconds;
    private final String tag;

    Connection(Socket socket, int idleTimeoutSeconds, int number) {
        this.socket = socket;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.tag = "[conn " + number + " " + socket.getRemoteSocketAddress() + "]";
    }

    @Override
    public void run() {
        log("open");
        int served = 0;
        try (socket) {
            socket.setSoTimeout(idleTimeoutSeconds * 1000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            RequestReader reader = new RequestReader(in);

            while (true) {
                RequestReader.Request req;
                try {
                    req = reader.read();
                } catch (RequestReader.BadRequest e) {
                    log("unframeable request: " + e.getMessage() + " -> " + e.status + ", closing");
                    write(out, new Response(e.status, e.getMessage(), false), false);
                    out.flush();
                    return;
                }
                if (req == null) {
                    log("client closed after " + served + " responses");
                    return;
                }
                boolean keepAlive = wantsKeepAlive(req);
                Response resp = handle(req);
                write(out, resp, keepAlive);
                served++;
                log(req.method() + " " + req.target() + " -> " + resp.status() + (keepAlive ? "" : ", closing"));
                if (!keepAlive) {
                    out.flush();
                    return;
                }
                if (in.available() == 0) out.flush();
            }
        } catch (SocketTimeoutException e) {
            log("idle for " + idleTimeoutSeconds + "s after " + served + " responses, closing");
        } catch (EOFException e) {
            log(e.getMessage());
        } catch (IOException e) {
            log("I/O error: " + e.getMessage());
        }
    }

    private static Response handle(RequestReader.Request req) {
        // HTTP/1.1 requires exactly one Host header (RFC 9112 section 3.2).
        int hosts = req.all("host").size();
        if (req.version().equals("HTTP/1.1") && hosts != 1) {
            return new Response(400, hosts == 0 ? "missing Host header" : "more than one Host header", false);
        }
        String target = req.target();
        if (!target.startsWith("/")) return new Response(400, "request target must start with /", false);
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        String query = q < 0 ? "" : target.substring(q + 1);

        if (!Calculator.isRoute(path)) return new Response(404, "no such operation: " + path, false);
        boolean head = req.method().equals("HEAD");
        if (!req.method().equals("GET") && !head) {
            return new Response(405, req.method() + " not allowed, use GET", false);
        }
        Calculator.Result r = Calculator.compute(path, query);
        return new Response(r.status(), r.body(), head);
    }

    /** HTTP/1.1 is persistent unless told otherwise; HTTP/1.0 only if it asks. */
    private static boolean wantsKeepAlive(RequestReader.Request req) {
        boolean close = false, keepAlive = false;
        for (String v : req.all("connection")) {
            for (String token : v.split(",")) {
                String t = token.strip().toLowerCase(Locale.ROOT);
                if (t.equals("close")) close = true;
                if (t.equals("keep-alive")) keepAlive = true;
            }
        }
        if (close) return false;
        return req.version().equals("HTTP/1.1") || keepAlive;
    }

    private void write(OutputStream out, Response r, boolean keepAlive) throws IOException {
        byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder(160)
                .append("HTTP/1.1 ").append(r.status()).append(' ').append(REASONS.get(r.status())).append("\r\n")
                .append("Content-Type: text/plain; charset=utf-8\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n");
        if (r.status() == 405) h.append("Allow: GET, HEAD\r\n");
        if (keepAlive) {
            h.append("Connection: keep-alive\r\n")
             .append("Keep-Alive: timeout=").append(idleTimeoutSeconds).append("\r\n");
        } else {
            h.append("Connection: close\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (!r.headOnly()) out.write(body);
    }

    private void log(String msg) {
        System.err.println(tag + " " + msg);
    }
}
