package calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The four arithmetic routes. Pure logic: no sockets, no HTTP framing. */
final class Calculator {
    private Calculator() {}

    record Result(int status, String body) {}

    private static final Set<String> ROUTES = Set.of("/add", "/sub", "/mul", "/div");

    /** Plain decimals only, bounded so a request cannot ask for a huge computation. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,30}(\\.\\d{1,30})?");

    static boolean isRoute(String path) {
        return ROUTES.contains(path);
    }

    static Result compute(String path, String query) {
        Map<String, String> params;
        try {
            params = parseQuery(query);
        } catch (IllegalArgumentException e) {
            return new Result(400, "bad percent-encoding in query");
        }
        String a = params.get("a");
        String b = params.get("b");
        if (a == null || b == null) return new Result(400, "query must have both a and b");
        if (!NUMBER.matcher(a).matches()) return new Result(400, "a is not a number: " + a);
        if (!NUMBER.matcher(b).matches()) return new Result(400, "b is not a number: " + b);

        BigDecimal x = new BigDecimal(a);
        BigDecimal y = new BigDecimal(b);
        BigDecimal r = switch (path) {
            case "/add" -> x.add(y);
            case "/sub" -> x.subtract(y);
            case "/mul" -> x.multiply(y);
            case "/div" -> {
                if (y.signum() == 0) yield null;
                yield x.divide(y, MathContext.DECIMAL64);
            }
            default -> throw new IllegalArgumentException("not a route: " + path);
        };
        if (r == null) return new Result(400, "division by zero");
        return new Result(200, format(r));
    }

    /** 5, 3.5, -2: no trailing zeros, no exponent. */
    private static String format(BigDecimal r) {
        if (r.signum() == 0) return "0";
        return r.stripTrailingZeros().toPlainString();
    }

    /** First occurrence of each name wins. */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(name, value);
        }
        return out;
    }
}
