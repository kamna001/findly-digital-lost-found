package com.foundly;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class LostFoundServer {
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final long SESSION_LIFETIME_SECONDS = 12 * 60 * 60;
    private static final long OTP_LIFETIME_SECONDS = 5 * 60;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+91[6-9]\\d{9}$");
    private static final Pattern MSG91_SUCCESS_PATTERN =
            Pattern.compile("\"(?:type|status)\"\\s*:\\s*\"success\"", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Path DATA_DIR = Path.of("data");
    private static final Path USERS_FILE = DATA_DIR.resolve("users.csv");
    private static final Map<String, User> USERS = new ConcurrentHashMap<>();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Otp> OTPS = new ConcurrentHashMap<>();
    private static final List<Item> ITEMS = new ArrayList<>();
    private static final boolean EXPOSE_DEMO_OTP =
            Boolean.parseBoolean(System.getProperty("app.devOtp", "true"));
    private static final String MSG91_AUTH_KEY = System.getenv().getOrDefault("MSG91_AUTH_KEY", "").trim();
    private static final String MSG91_TEMPLATE_ID = System.getenv().getOrDefault("MSG91_TEMPLATE_ID", "").trim();
    private static final String MSG91_OTP_VARIABLE = System.getenv().getOrDefault("MSG91_OTP_VARIABLE", "VAR1").trim();

    private LostFoundServer() {
    }

    public static void main(String[] args) throws IOException {
        if (MSG91_AUTH_KEY.isEmpty() != MSG91_TEMPLATE_ID.isEmpty()) {
            throw new IllegalStateException("Set both MSG91_AUTH_KEY and MSG91_TEMPLATE_ID, or leave both unset for demo mode.");
        }
        Files.createDirectories(DATA_DIR);
        loadUsers();
        seedItems();

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", LostFoundServer::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Findly is running at http://localhost:" + port);
        if (!msg91Configured() && EXPOSE_DEMO_OTP) {
            System.out.println("Demo OTP mode is enabled. Disable with -Dapp.devOtp=false before production.");
        } else if (msg91Configured()) {
            System.out.println("MSG91 SMS delivery is configured.");
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.startsWith("/api/")) {
                handleApi(exchange, method, path);
                return;
            }
            serveStatic(exchange, path);
        } catch (IllegalArgumentException ex) {
            sendJson(exchange, 400, jsonMessage(ex.getMessage()));
        } catch (SmsDeliveryException ex) {
            System.err.println("MSG91 OTP delivery failed: " + ex.getMessage());
            sendJson(exchange, 502, jsonMessage("We couldn't send your SMS code. Please check the number or try again later."));
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            sendJson(exchange, 500, jsonMessage("Something went wrong. Please try again."));
        } finally {
            exchange.close();
        }
    }

    private static void handleApi(HttpExchange exchange, String method, String path)
            throws IOException, SmsDeliveryException {
        if (method.equals("POST")) {
            Map<String, String> form = parseForm(exchange);
            switch (path) {
                case "/api/register" -> register(exchange, form);
                case "/api/login/password" -> passwordLogin(exchange, form);
                case "/api/login/otp/request" -> requestOtp(exchange, form);
                case "/api/login/otp/verify" -> verifyOtp(exchange, form);
                case "/api/logout" -> logout(exchange);
                case "/api/items" -> createItem(exchange, form);
                default -> sendJson(exchange, 404, jsonMessage("That endpoint was not found."));
            }
            return;
        }

        if (method.equals("GET") && path.equals("/api/me")) {
            User user = currentUser(exchange);
            if (user == null) {
                sendJson(exchange, 200, "{\"user\":null}");
            } else {
                sendJson(exchange, 200, "{\"user\":" + userJson(user) + "}");
            }
            return;
        }
        if (method.equals("GET") && path.equals("/api/items")) {
            if (currentUser(exchange) == null) {
                sendJson(exchange, 401, jsonMessage("Please sign in to continue."));
                return;
            }
            synchronized (ITEMS) {
                sendJson(exchange, 200, itemsJson());
            }
            return;
        }
        sendJson(exchange, 405, jsonMessage("Method not allowed."));
    }

    private static void register(HttpExchange exchange, Map<String, String> form) throws IOException {
        String name = required(form, "name").trim();
        String email = normalizeEmail(required(form, "email"));
        String phone = normalizePhone(required(form, "phone"));
        String password = required(form, "password");
        if (name.length() < 2 || name.length() > 60) {
            throw new IllegalArgumentException("Please enter a name between 2 and 60 characters.");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        if (!isValidIndianPhone(phone)) {
            throw new IllegalArgumentException("Enter a valid Indian mobile number, such as +919876543210.");
        }
        if (password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("Your password must be between 8 and 128 characters.");
        }

        synchronized (USERS) {
            if (USERS.containsKey(email) || findUserByPhone(phone) != null) {
                sendJson(exchange, 409, jsonMessage("An account with that email or phone number already exists."));
                return;
            }
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            User user = new User(email, name, phone, salt, hashPassword(password, salt));
            USERS.put(email, user);
            saveUsers();
            createSession(exchange, user);
        }
    }

    private static void passwordLogin(HttpExchange exchange, Map<String, String> form) throws IOException {
        String email = normalizeEmail(required(form, "email"));
        String password = required(form, "password");
        User user = USERS.get(email);
        if (user == null || !MessageDigest.isEqual(user.passwordHash(), hashPassword(password, user.salt()))) {
            sendJson(exchange, 401, jsonMessage("Email or password doesn't match."));
            return;
        }
        createSession(exchange, user);
    }

    private static void requestOtp(HttpExchange exchange, Map<String, String> form)
            throws IOException, SmsDeliveryException {
        String phone = normalizePhone(required(form, "phone"));
        if (!isValidIndianPhone(phone)) {
            throw new IllegalArgumentException("Enter a valid Indian mobile number, such as +919876543210.");
        }
        if (findUserByPhone(phone) == null) {
            sendJson(exchange, 404, jsonMessage("No account found for that phone number. Create an account first."));
            return;
        }

        if (!msg91Configured() && !EXPOSE_DEMO_OTP) {
            sendJson(exchange, 503, jsonMessage("SMS delivery is not configured. Please contact the site administrator."));
            return;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        if (msg91Configured()) {
            sendMsg91Otp(phone, code);
        } else {
            System.out.println("Demo SMS sign-in code for " + phone + ": " + code);
        }
        OTPS.put(phone, new Otp(hashOtp(code), Instant.now().getEpochSecond() + OTP_LIFETIME_SECONDS, 0));
        String body = "{\"message\":\"A sign-in code was sent to your phone. It expires in 5 minutes.\"";
        if (!msg91Configured() && EXPOSE_DEMO_OTP) {
            body += ",\"demoCode\":\"" + code + "\"";
        }
        sendJson(exchange, 200, body + "}");
    }

    private static void verifyOtp(HttpExchange exchange, Map<String, String> form) throws IOException {
        String phone = normalizePhone(required(form, "phone"));
        String code = required(form, "code").trim();
        Otp otp = OTPS.get(phone);
        if (otp == null || otp.expiresAt() < Instant.now().getEpochSecond() || otp.attempts() >= 5) {
            OTPS.remove(phone);
            sendJson(exchange, 401, jsonMessage("That code has expired. Request a new one."));
            return;
        }
        if (!MessageDigest.isEqual(otp.hash(), hashOtp(code))) {
            if (otp.attempts() + 1 >= 5) {
                OTPS.remove(phone);
            } else {
                OTPS.put(phone, new Otp(otp.hash(), otp.expiresAt(), otp.attempts() + 1));
            }
            sendJson(exchange, 401, jsonMessage("That code doesn't match. Please try again."));
            return;
        }
        OTPS.remove(phone);
        User user = findUserByPhone(phone);
        if (user == null) {
            sendJson(exchange, 401, jsonMessage("No account found for that phone number."));
            return;
        }
        createSession(exchange, user);
    }

    private static void sendMsg91Otp(String phone, String code) throws SmsDeliveryException {
        String mobile = phone.substring(1);
        String endpoint = "https://control.msg91.com/api/v5/otp?template_id="
                + urlEncode(MSG91_TEMPLATE_ID)
                + "&mobile=" + urlEncode(mobile)
                + "&authkey=" + urlEncode(MSG91_AUTH_KEY)
                + "&otp=" + urlEncode(code);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || (!MSG91_SUCCESS_PATTERN.matcher(response.body()).find()
                    && response.body().toLowerCase(Locale.ROOT).contains("error"))) {
                throw new SmsDeliveryException("MSG91 rejected the send request (HTTP " + response.statusCode() + ").");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SmsDeliveryException("The MSG91 request was interrupted.", ex);
        } catch (IOException ex) {
            throw new SmsDeliveryException("Could not reach MSG91.", ex);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean msg91Configured() {
        return !MSG91_AUTH_KEY.isEmpty() && !MSG91_TEMPLATE_ID.isEmpty();
    }

    private static boolean isValidIndianPhone(String phone) {
        return PHONE_PATTERN.matcher(phone).matches();
    }

    private static void logout(HttpExchange exchange) throws IOException {
        String token = sessionToken(exchange);
        if (token != null) {
            SESSIONS.remove(token);
        }
        exchange.getResponseHeaders().add("Set-Cookie",
                "foundly_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        sendJson(exchange, 200, "{\"message\":\"Signed out successfully.\"}");
    }

    private static void createItem(HttpExchange exchange, Map<String, String> form) throws IOException {
        User owner = currentUser(exchange);
        if (owner == null) {
            sendJson(exchange, 401, jsonMessage("Please sign in to continue."));
            return;
        }
        String type = required(form, "type");
        String title = required(form, "title").trim();
        String category = required(form, "category").trim();
        String location = required(form, "location").trim();
        String description = required(form, "description").trim();
        String date = required(form, "date").trim();
        if (!type.equals("Lost") && !type.equals("Found")) {
            throw new IllegalArgumentException("Choose whether the item was lost or found.");
        }
        if (title.length() < 3 || title.length() > 80 || location.length() > 100
                || description.length() < 8 || description.length() > 500) {
            throw new IllegalArgumentException("Check the item title, location, and description.");
        }
        Item item = new Item(UUID.randomUUID().toString(), type, title, category, location, description,
                date, "Open", owner.name(), owner.email());
        synchronized (ITEMS) {
            ITEMS.add(0, item);
            sendJson(exchange, 201, "{\"item\":" + itemJson(item) + "}");
        }
    }

    private static void createSession(HttpExchange exchange, User user) throws IOException {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        SESSIONS.put(token, new Session(user.email(), Instant.now().getEpochSecond() + SESSION_LIFETIME_SECONDS));
        exchange.getResponseHeaders().add("Set-Cookie", "foundly_session=" + token
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + SESSION_LIFETIME_SECONDS);
        sendJson(exchange, 200, "{\"user\":" + userJson(user) + "}");
    }

    private static User currentUser(HttpExchange exchange) {
        String token = sessionToken(exchange);
        if (token == null) {
            return null;
        }
        Session session = SESSIONS.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAt() < Instant.now().getEpochSecond()) {
            SESSIONS.remove(token);
            return null;
        }
        return USERS.get(session.email());
    }

    private static String sessionToken(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].equals("foundly_session")) {
                return parts[1];
            }
        }
        return null;
    }

    private static void serveStatic(HttpExchange exchange, String requestPath) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
            send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String relative = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
        Path root = Path.of("src", "main", "resources", "static").toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found.".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String contentType = switch (file.getFileName().toString().toLowerCase(Locale.ROOT)) {
            case "index.html" -> "text/html; charset=utf-8";
            case "styles.css" -> "text/css; charset=utf-8";
            case "app.js" -> "text/javascript; charset=utf-8";
            case "favicon.svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
        byte[] content = Files.readAllBytes(file);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, -1);
        } else {
            send(exchange, 200, contentType, content);
        }
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(8193);
        if (body.length > 8192) {
            throw new IllegalArgumentException("Request is too large.");
        }
        Map<String, String> fields = new HashMap<>();
        String decoded = new String(body, StandardCharsets.UTF_8);
        for (String pair : decoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            fields.put(key, value);
        }
        return fields;
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Please complete all required fields.");
        }
        return value;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePhone(String phone) {
        String normalized = phone.trim().replaceAll("[\\s()-]", "");
        if (normalized.matches("[6-9]\\d{9}")) {
            return "+91" + normalized;
        }
        if (normalized.matches("91[6-9]\\d{9}")) {
            return "+" + normalized;
        }
        return normalized;
    }

    private static User findUserByPhone(String phone) {
        return USERS.values().stream().filter(user -> user.phone().equals(phone)).findFirst().orElse(null);
    }

    private static byte[] hashPassword(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not securely hash the password.", ex);
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] hashOtp(String code) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not verify the sign-in code.", ex);
        }
    }

    private static void loadUsers() throws IOException {
        if (!Files.exists(USERS_FILE)) {
            return;
        }
        for (String line : Files.readAllLines(USERS_FILE, StandardCharsets.UTF_8)) {
            String[] parts = line.split(",", -1);
            if (parts.length != 4 && parts.length != 5) {
                continue;
            }
            try {
                String email = decode(parts[0]);
                String phone = parts.length == 5 ? decode(parts[2]) : "";
                int saltIndex = parts.length == 5 ? 3 : 2;
                USERS.put(email, new User(email, decode(parts[1]), phone,
                        Base64.getDecoder().decode(parts[saltIndex]), Base64.getDecoder().decode(parts[saltIndex + 1])));
            } catch (IllegalArgumentException ignored) {
                System.err.println("Skipping an invalid account record in " + USERS_FILE);
            }
        }
    }

    private static void saveUsers() throws IOException {
        List<String> rows = new ArrayList<>();
        for (User user : USERS.values()) {
            rows.add(encode(user.email()) + "," + encode(user.name()) + "," + encode(user.phone()) + ","
                    + Base64.getEncoder().encodeToString(user.salt()) + ","
                    + Base64.getEncoder().encodeToString(user.passwordHash()));
        }
        Path temporary = DATA_DIR.resolve("users.csv.tmp");
        Files.write(temporary, rows, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, USERS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(temporary, USERS_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void seedItems() {
        ITEMS.add(new Item("seed-1", "Found", "A little piece of home", "Keys", "North Library · Floor 2",
                "A brass keyring with a small blue house charm, picked up near the reading tables.", "2026-10-28",
                "Open", "Maya R.", ""));
        ITEMS.add(new Item("seed-2", "Lost", "Silver film camera", "Electronics", "Riverside Café",
                "Small silver point-and-shoot camera. It has a faded sticker on the back.", "2026-10-27",
                "Open", "Jordan L.", ""));
        ITEMS.add(new Item("seed-3", "Found", "Sketchbook with a green cover", "Books", "Studio Building · Room 104",
                "Found after the afternoon workshop. There are a few charcoal sketches inside.", "2026-10-26",
                "Open", "Sam K.", ""));
    }

    private static String itemsJson() {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int i = 0; i < ITEMS.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(itemJson(ITEMS.get(i)));
        }
        return json.append("]}").toString();
    }

    private static String itemJson(Item item) {
        return "{\"id\":" + quote(item.id()) + ",\"type\":" + quote(item.type())
                + ",\"title\":" + quote(item.title()) + ",\"category\":" + quote(item.category())
                + ",\"location\":" + quote(item.location()) + ",\"description\":" + quote(item.description())
                + ",\"date\":" + quote(item.date()) + ",\"status\":" + quote(item.status())
                + ",\"owner\":" + quote(item.owner()) + ",\"email\":" + quote(item.email()) + "}";
    }

    private static String userJson(User user) {
        return "{\"name\":" + quote(user.name()) + ",\"email\":" + quote(user.email())
                + ",\"phone\":" + quote(user.phone()) + "}";
    }

    private static String jsonMessage(String message) {
        return "{\"message\":" + quote(message) + "}";
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private record User(String email, String name, String phone, byte[] salt, byte[] passwordHash) {
    }

    private record Session(String email, long expiresAt) {
    }

    private record Otp(byte[] hash, long expiresAt, int attempts) {
    }

    private static final class SmsDeliveryException extends Exception {
        private SmsDeliveryException(String message) {
            super(message);
        }

        private SmsDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record Item(String id, String type, String title, String category, String location, String description,
                        String date, String status, String owner, String email) {
    }
}
