import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Raj AI Studio — zero-dependency Java gateway.
 *
 * Serves the root static frontend and proxies /api/generate to the Python AI
 * microservice on port 5000. Persists generation metadata to data/history.json.
 */
public class RajGatewayServer {

    private static final String MODELS_OK = "flux-realism,flux,any-dark,turbo";
    private static final String PYTHON_GENERATE_URL = "http://127.0.0.1:5000/api/generate";
    private static final int PYTHON_TIMEOUT_MS = 120_000;

    private static Path ROOT;
    private static final Object HISTORY_LOCK = new Object();

    public static void main(String[] args) throws Exception {
        ROOT = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        int port = readPortConfig();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/styles", new StylesHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/api/generate", new GenerateHandler());
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));

        System.out.println("================================================================");
        System.out.println("  Raj AI Studio — Java Gateway");
        System.out.println("  URL      : http://localhost:" + port);
        System.out.println("  Root     : " + ROOT);
        System.out.println("  Python   : " + PYTHON_GENERATE_URL);
        System.out.println("  Mode     : FULL-STACK JAVA + PYTHON + JSON");
        System.out.println("================================================================");
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down Raj AI Studio gateway...");
            server.stop(1);
        }));
    }

    private static int readPortConfig() {
        try {
            String raw = new String(Files.readAllBytes(ROOT.resolve("data/config.json")), StandardCharsets.UTF_8);
            Object parsed = new JsonParser(raw).parse();
            Object val = getPath(parsed, "ports", "java_port");
            if (val == null) val = getPath(parsed, "java_port");
            if (val instanceof Number) return ((Number) val).intValue();
        } catch (Exception ignored) {
            // fallback below
        }
        return 8080;
    }

    /* --------------------------------- Handlers -------------------------------- */

    static class HealthHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            boolean pythonOnline = false;
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://127.0.0.1:5000/health");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1200);
                conn.setReadTimeout(1200);
                pythonOnline = conn.getResponseCode() == 200;
            } catch (Exception ignored) {
                // offline
            } finally {
                if (conn != null) conn.disconnect();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("gateway", "java");
            body.put("python", pythonOnline ? "online" : "offline");
            body.put("ports", mapOf("java", 8080, "python", 5000));
            body.put("engine", "flux-realism");
            body.put("developer", "Raj Gautam");
            sendJson(ex, 200, body);
        }
    }

    static class StylesHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
            try {
                String raw = new String(Files.readAllBytes(ROOT.resolve("data/styles.json")), StandardCharsets.UTF_8);
                Object parsed = new JsonParser(raw).parse();
                Object styles = getPath(parsed, "styles");
                if (!(styles instanceof List)) styles = parsed;
                sendJson(ex, 200, mapOf("status", "success", "styles", styles));
            } catch (Exception err) {
                sendJson(ex, 500, mapOf("status", "error", "message", "Unable to read styles.json: " + err.getMessage()));
            }
        }
    }

    static class HistoryHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
            try {
                synchronized (HISTORY_LOCK) {
                    Path file = ROOT.resolve("data/history.json");
                    if (!Files.exists(file)) {
                        sendJson(ex, 200, mapOf("status", "success", "history", new ArrayList<>()));
                        return;
                    }
                    Object parsed = new JsonParser(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).parse();
                    Object records = (parsed instanceof Map) ? getPath(parsed, "history") : parsed;
                    if (!(records instanceof List)) records = new ArrayList<>();
                    List<Object> list = new ArrayList<>((List<Object>) records);
                    int from = Math.max(0, list.size() - 100);
                    sendJson(ex, 200, mapOf("status", "success", "history", list.subList(from, list.size())));
                }
            } catch (Exception err) {
                sendJson(ex, 500, mapOf("status", "error", "message", "Unable to read history.json: " + err.getMessage()));
            }
        }
    }

    static class GenerateHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, mapOf("status", "error", "message", "Use POST for /api/generate"));
                return;
            }

            Map<String, Object> payload;
            try {
                String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
                Object parsed = new JsonParser(body).parse();
                if (!(parsed instanceof Map)) throw new IllegalArgumentException("JSON object required");
                payload = (Map<String, Object>) parsed;
            } catch (Exception err) {
                sendJson(ex, 400, mapOf("status", "error", "message", "Invalid JSON payload: " + err.getMessage()));
                return;
            }

            String validationError = validate(payload);
            if (validationError != null) {
                sendJson(ex, 400, mapOf("status", "error", "message", validationError));
                return;
            }

            try {
                String pythonBody = JsonWriter.write(payload);
                String responseText = postToPython(pythonBody);
                Object parsedResponse = new JsonParser(responseText).parse();
                if (!(parsedResponse instanceof Map)) throw new IOException("Python returned invalid JSON");
                Map<String, Object> result = (Map<String, Object>) parsedResponse;

                if (!"success".equals(String.valueOf(result.getOrDefault("status", "")))) {
                    sendJson(ex, 502, mapOf(
                        "status", "error",
                        "message", String.valueOf(result.getOrDefault("message", "AI service failed"))
                    ));
                    return;
                }

                Map<String, Object> record = new LinkedHashMap<>();
                record.put("prompt", String.valueOf(payload.getOrDefault("originalPrompt", payload.get("prompt"))));
                record.put("enhancedPrompt", String.valueOf(payload.get("prompt")));
                record.put("imageUrl", String.valueOf(result.getOrDefault("imageUrl", "")));
                record.put("seed", result.getOrDefault("seed", payload.get("seed")));
                record.put("width", payload.get("width"));
                record.put("height", payload.get("height"));
                record.put("model", result.getOrDefault("engine", payload.get("model")));
                record.put("style", String.valueOf(payload.getOrDefault("style", "")));
                record.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date()));
                appendHistory(record);

                Map<String, Object> response = new LinkedHashMap<>(result);
                response.putIfAbsent("status", "success");
                response.putIfAbsent("mode", "fullstack");
                sendJson(ex, 200, response);
            } catch (Exception err) {
                sendJson(ex, 502, mapOf("status", "error", "message", "Python AI service error: " + err.getMessage()));
            }
        }
    }

    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { noContent(ex); return; }
            String path = ex.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";

            Path candidate = ROOT.resolve("." + path).normalize();
            if (!candidate.startsWith(ROOT)) {
                sendText(ex, 403, "Forbidden");
                return;
            }
            File file = candidate.toFile();
            if (!file.isFile()) {
                // SPA-ish fallback: unknown paths that are not API calls go to index.html
                file = ROOT.resolve("index.html").toFile();
            }
            if (!file.isFile()) {
                sendText(ex, 404, "Not found");
                return;
            }

            byte[] bytes = Files.readAllBytes(file.toPath());
            ex.getResponseHeaders().set("Content-Type", contentType(file.getName()));
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    /* --------------------------------- Helpers --------------------------------- */

    private static String validate(Map<String, Object> p) {
        Object promptObj = p.get("prompt");
        if (!(promptObj instanceof String) || ((String) promptObj).trim().isEmpty()) return "prompt is required";
        if (((String) promptObj).length() > 4000) return "prompt is too long (max 4000 characters)";

        Object w = p.getOrDefault("width", 768);
        Object h = p.getOrDefault("height", 768);
        if (!(w instanceof Number) || !(h instanceof Number)) return "width and height must be numbers";
        int wi = ((Number) w).intValue();
        int hi = ((Number) h).intValue();
        if (wi < 256 || wi > 2048 || hi < 256 || hi > 2048) return "width and height must be between 256 and 2048";

        Object modelObj = p.getOrDefault("model", "flux-realism");
        if (!(modelObj instanceof String) || ((String) modelObj).trim().isEmpty() || ((String) modelObj).length() > 120) {
            return "model must be a non-empty string (max 120 chars)";
        }
        return null;
    }

    private static String postToPython(String json) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(PYTHON_GENERATE_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(PYTHON_TIMEOUT_MS);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = new String(readAll(stream), StandardCharsets.UTF_8);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + body);
        return body;
    }

    private static void appendHistory(Map<String, Object> record) throws IOException {
        synchronized (HISTORY_LOCK) {
            Path file = ROOT.resolve("data/history.json");
            Files.createDirectories(file.getParent());
            List<Object> list = new ArrayList<>();
            Map<String, Object> wrapper = new LinkedHashMap<>();
            if (Files.exists(file) && Files.size(file) > 0) {
                Object parsed = new JsonParser(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).parse();
                if (parsed instanceof Map) {
                    wrapper.putAll((Map<String, Object>) parsed);
                    Object h = wrapper.get("history");
                    if (h instanceof List) list.addAll((List<Object>) h);
                } else if (parsed instanceof List) {
                    list.addAll((List<Object>) parsed);
                }
            }
            list.add(record);
            while (list.size() > 200) list.remove(0);
            wrapper.put("status", "success");
            wrapper.put("history", list);
            Files.write(file, JsonWriter.write(wrapper).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Object getPath(Object obj, String... keys) {
        Object cur = obj;
        for (String key : keys) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<?, ?>) cur).get(key);
        }
        return cur;
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = JsonWriter.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void noContent(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    /* --------------------------- Minimal JSON reader --------------------------- */

    static class JsonParser {
        private final String src;
        private int pos = 0;

        JsonParser(String src) { this.src = src == null ? "" : src; }

        Object parse() {
            skipWs();
            Object value = readValue();
            skipWs();
            return value;
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        private Object readValue() {
            skipWs();
            if (pos >= src.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = src.charAt(pos);
            switch (c) {
                case '{': return readObject();
                case '[': return readArray();
                case '"': return readString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
            while (pos < src.length()) {
                skipWs();
                String key = readString();
                skipWs();
                if (src.charAt(pos++) != ':') throw new IllegalArgumentException("expected ':'");
                map.put(key, readValue());
                skipWs();
                if (pos >= src.length()) break;
                char sep = src.charAt(pos++);
                if (sep == '}') break;
                if (sep != ',') throw new IllegalArgumentException("expected ',' or '}'");
            }
            return map;
        }

        private List<Object> readArray() {
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
            while (pos < src.length()) {
                list.add(readValue());
                skipWs();
                if (pos >= src.length()) break;
                char sep = src.charAt(pos++);
                if (sep == ']') break;
                if (sep != ',') throw new IllegalArgumentException("expected ',' or ']'");
            }
            return list;
        }

        private String readString() {
            if (src.charAt(pos++) != '"') throw new IllegalArgumentException("expected string");
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) break;
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > src.length()) throw new IllegalArgumentException("bad unicode escape");
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Number readNumber() {
            int start = pos;
            while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) pos++;
            String token = src.substring(start, pos);
            if (token.isEmpty()) throw new IllegalArgumentException("expected value");
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) return Double.parseDouble(token);
                return Long.parseLong(token);
            } catch (NumberFormatException err) {
                throw new IllegalArgumentException("bad number: " + token);
            }
        }

        private void expect(String word) {
            if (!src.startsWith(word, pos)) throw new IllegalArgumentException("expected " + word);
            pos += word.length();
        }
    }

    /* ---------------------------- Minimal JSON writer -------------------------- */

    static class JsonWriter {
        static String write(Object value) {
            StringBuilder sb = new StringBuilder();
            append(sb, value);
            return sb.toString();
        }

        private static void append(StringBuilder sb, Object value) {
            if (value == null) { sb.append("null"); return; }
            if (value instanceof String) { quote(sb, (String) value); return; }
            if (value instanceof Boolean || value instanceof Number) { sb.append(value); return; }
            if (value instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    append(sb, e.getValue());
                }
                sb.append('}');
                return;
            }
            if (value instanceof Iterable) {
                sb.append('[');
                boolean first = true;
                for (Object item : (Iterable<?>) value) {
                    if (!first) sb.append(',');
                    first = false;
                    append(sb, item);
                }
                sb.append(']');
                return;
            }
            quote(sb, String.valueOf(value));
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
