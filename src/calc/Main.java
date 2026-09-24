package calc;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Calculator over HTTP/1.1 keep-alive, written on a bare ServerSocket. */
public final class Main {
    private static final String USAGE = "usage: calc [-p PORT] [-t IDLE_TIMEOUT_SECONDS]";

    public static void main(String[] args) {
        int port = 8080;
        int idleSeconds = 60;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-p" -> port = Integer.parseInt(args[++i]);
                    case "-t" -> idleSeconds = Integer.parseInt(args[++i]);
                    case "-h" -> { System.out.println(USAGE); return; }
                    default -> usage("unexpected argument: " + args[i]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            usage("bad or missing option value");
        }
        if (port < 0 || port > 65535 || idleSeconds < 1) usage("port must be 0 to 65535, timeout at least 1");

        try (ServerSocket server = new ServerSocket(port);
             ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            System.err.println("calc: listening on port " + server.getLocalPort()
                    + ", idle timeout " + idleSeconds + "s");
            int n = 0;
            while (true) {
                Socket s = server.accept();
                pool.submit(new Connection(s, idleSeconds, ++n));
            }
        } catch (IOException e) {
            System.err.println("calc: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void usage(String problem) {
        System.err.println("calc: " + problem);
        System.err.println(USAGE);
        System.exit(2);
    }
}
