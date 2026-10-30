package io.github.vesaaa.cleanviddatv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞牛影视 (the media server of a fnOS NAS): the signed-in account and the few calls the top shelf needs. The API is
 * not published; its shape and the request signature (Authx) come from the open clients built on it, and the key
 * pair is the one those clients use. Viewers can replace the pair in 飞牛影视 settings if the server changes it.
 * Blocking calls: run them off the main thread.
 */
final class Feiniu {

    static final String DEFAULT_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    static final String DEFAULT_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237";
    static final int PORT = 5666;
    static final String PATH = "/v";
    static final String PREFS = "feiniu";
    private static final String KEY_BASE = "base", KEY_USER = "user", KEY_PASSWORD = "password",
            KEY_TOKEN = "token", KEY_API_KEY = "api_key", KEY_API_SECRET = "api_secret";
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Feiniu() { }

    /** The server answered, but not with success: code -2 is a token it no longer accepts, 5000 a bad signature. */
    static final class ApiException extends IOException {
        final int code;

        ApiException(int code, String message) {
            super(message == null || message.isEmpty() ? "code " + code : message);
            this.code = code;
        }

        boolean authFailed() {
            return code == -2;
        }

        boolean badSign() {
            return code == 5000;
        }
    }

    // ---- the saved account -----------------------------------------------------------------------------------

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return !p.getString(KEY_BASE, "").isEmpty() && !p.getString(KEY_USER, "").isEmpty()
                && !p.getString(KEY_TOKEN, "").isEmpty();
    }

    static String base(Context ctx) {
        return prefs(ctx).getString(KEY_BASE, "");
    }

    static String user(Context ctx) {
        return prefs(ctx).getString(KEY_USER, "");
    }

    static String apiKey(Context ctx) {
        String v = prefs(ctx).getString(KEY_API_KEY, "");
        return v.isEmpty() ? DEFAULT_KEY : v;
    }

    static String apiSecret(Context ctx) {
        String v = prefs(ctx).getString(KEY_API_SECRET, "");
        return v.isEmpty() ? DEFAULT_SECRET : v;
    }

    static boolean customKeys(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return !p.getString(KEY_API_KEY, "").isEmpty() || !p.getString(KEY_API_SECRET, "").isEmpty();
    }

    /** An empty key or secret goes back to the built-in one. */
    static void setKeys(Context ctx, String key, String secret) {
        prefs(ctx).edit().putString(KEY_API_KEY, key == null ? "" : key.trim())
                .putString(KEY_API_SECRET, secret == null ? "" : secret.trim()).apply();
    }

    /** The password is kept (encrypted with a key only this app can use) to sign in again when the token expires. */
    static void save(Context ctx, String base, String user, String password, String token) {
        prefs(ctx).edit().putString(KEY_BASE, base).putString(KEY_USER, user)
                .putString(KEY_PASSWORD, SecretBox.seal(password)).putString(KEY_TOKEN, token).apply();
    }

    static void signOut(Context ctx) {
        prefs(ctx).edit().remove(KEY_BASE).remove(KEY_USER).remove(KEY_PASSWORD).remove(KEY_TOKEN).apply();
    }

    /** A client for the saved account, or null when signed out. */
    static Client client(Context ctx) {
        if (!signedIn(ctx)) return null;
        SharedPreferences p = prefs(ctx);
        return new Client(p.getString(KEY_BASE, ""), apiKey(ctx), apiSecret(ctx), p.getString(KEY_TOKEN, ""));
    }

    /** Signs in again with the saved password after the server dropped the token. False when that fails too. */
    static boolean renew(Context ctx, Client client) {
        SharedPreferences p = prefs(ctx);
        String password = SecretBox.open(p.getString(KEY_PASSWORD, ""));
        if (password == null) return false;
        try {
            client.token = login(client.base, client.key, client.secret, p.getString(KEY_USER, ""), password);
        } catch (IOException e) {
            return false;
        }
        p.edit().putString(KEY_TOKEN, client.token).apply();
        return true;
    }

    // ---- addresses -------------------------------------------------------------------------------------------

    /**
     * What a viewer typed as the server, as http://host:port with no path: "192.168.1.9" gets the default port,
     * a pasted ".../v" page address loses its path. Null when it is not a host.
     */
    static String normalise(String typed) {
        if (typed == null) return null;
        String s = typed.trim();
        if (s.isEmpty()) return null;
        String scheme = "http://";
        if (s.regionMatches(true, 0, "https://", 0, 8)) {
            scheme = "https://";
            s = s.substring(8);
        } else if (s.regionMatches(true, 0, "http://", 0, 7)) {
            s = s.substring(7);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.isEmpty() || !s.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) return null;
        if (s.indexOf(':') < 0) s = s + ":" + PORT;
        return scheme + s;
    }

    /** host:port of a base, for the settings rows. */
    static String display(String base) {
        if (base == null) return "";
        return base.replaceFirst("^https?://", "");
    }

    // ---- the signature ---------------------------------------------------------------------------------------

    static String md5(String text) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The Authx header: md5 of key_path_nonce_timestamp_md5(body)_secret, the path from /v on, the body as sent. */
    static String authx(String key, String secret, String path, String body, String nonce, long timestamp) {
        String sign = md5(key + "_" + path + "_" + nonce + "_" + timestamp + "_" + md5(body == null ? "" : body)
                + "_" + secret);
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + sign;
    }

    private static String nonce() {
        return String.valueOf(100000 + RANDOM.nextInt(900000));
    }

    // ---- calls -----------------------------------------------------------------------------------------------

    static final class Client {
        final String base, key, secret;
        volatile String token;

        Client(String base, String key, String secret, String token) {
            this.base = base;
            this.key = key;
            this.secret = secret;
            this.token = token == null ? "" : token;
        }

        /** GET (body null) or POST, signed; the response's data, or ApiException with its code. */
        Object call(String path, JSONObject body) throws IOException {
            String sent = null;
            if (body != null) {
                try {
                    body.put("nonce", nonce());
                } catch (JSONException e) {
                    throw new IOException(e);
                }
                sent = body.toString();
            }
            HttpURLConnection c = open(base + path);
            try {
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", token);
                c.setRequestProperty("Authx", authx(key, secret, path, sent, nonce(), System.currentTimeMillis()));
                if (sent != null) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(sent.getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                JSONObject json = new JSONObject(new String(read(c.getInputStream()), StandardCharsets.UTF_8));
                int code = json.optInt("code", -1);
                if (code != 0) throw new ApiException(code, json.optString("msg", ""));
                return json.opt("data");
            } catch (JSONException e) {
                throw new IOException("not a 飞牛影视 answer", e);
            } finally {
                c.disconnect();
            }
        }

        /** The continue-watching list, newest first. */
        List<FeiniuEntry> playing() throws IOException {
            Object data = call(PATH + "/api/v1/play/list", null);
            return entries(data instanceof JSONArray ? (JSONArray) data : new JSONArray());
        }

        /** The newest films and series across every library. */
        List<FeiniuEntry> recent(int count) throws IOException {
            JSONObject req = new JSONObject();
            try {
                req.put("tags", new JSONObject().put("type", new JSONArray().put("Movie").put("TV")));
                req.put("exclude_grouped_video", 1);
                req.put("sort_type", "DESC");
                req.put("sort_column", "create_time");
                req.put("page_size", count);
                req.put("page", 1);
            } catch (JSONException e) {
                throw new IOException(e);
            }
            Object data = call(PATH + "/api/v1/item/list", req);
            JSONArray list = data instanceof JSONObject ? ((JSONObject) data).optJSONArray("list") : null;
            return entries(list == null ? new JSONArray() : list);
        }

        /** An item's scraped artwork and text, and its parent (a season's series, an episode's season). */
        Art art(String guid) throws IOException {
            Object data = call(PATH + "/api/v1/item/" + guid, null);
            if (!(data instanceof JSONObject)) return new Art(null, null, null, null);
            JSONObject o = (JSONObject) data;
            return new Art(text(o, "backdrops"), text(o, "logos"), text(o, "overview"), text(o, "parent_guid"));
        }

        /** A poster or still from the server, scaled there to about this width. */
        byte[] image(String imagePath, int width) throws IOException {
            HttpURLConnection c = open(base + PATH + "/api/v1/sys/img" + imagePath + "?w=" + width);
            try {
                c.setRequestProperty("Authorization", token);
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                String type = c.getContentType();
                if (type != null && type.startsWith("application/json")) throw new ApiException(-2, "image refused");
                return read(c.getInputStream());
            } finally {
                c.disconnect();
            }
        }
    }

    /** What the server scraped for a film, a series or a season; each part null when it has none. */
    static final class Art {
        final String backdrop, logo, overview, parent;

        Art(String backdrop, String logo, String overview, String parent) {
            this.backdrop = backdrop;
            this.logo = logo;
            this.overview = overview;
            this.parent = parent;
        }
    }

    private static String text(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, "").trim();
        return v.isEmpty() ? null : v;
    }

    /** Signs in and returns the token. */
    static String login(String base, String key, String secret, String user, String password) throws IOException {
        Client c = new Client(base, key, secret, "");
        JSONObject req = new JSONObject();
        try {
            req.put("app_name", "trimemedia-web").put("username", user).put("password", password);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Object data = c.call(PATH + "/api/v1/login", req);
        String token = data instanceof JSONObject ? ((JSONObject) data).optString("token", "") : "";
        if (token.isEmpty()) throw new IOException("no token");
        return token;
    }

    /** A 飞牛影视 server's name (the NAS's) and version, which needs no account; IOException when it is not one. */
    static String[] probe(String base, String key, String secret) throws IOException {
        Client c = new Client(base, key, secret, "");
        Object version = c.call(PATH + "/api/v1/sys/version", null);
        String v = version instanceof JSONObject ? ((JSONObject) version).optString("version", "") : "";
        String name = "";
        try {
            Object config = c.call(PATH + "/api/v1/sys/config", null);
            if (config instanceof JSONObject) name = ((JSONObject) config).optString("server_name", "");
        } catch (IOException ignored) {
            // the version is enough to know it is one
        }
        return new String[]{name, v};
    }

    private static List<FeiniuEntry> entries(JSONArray list) {
        List<FeiniuEntry> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            out.add(new FeiniuEntry(o.optString("guid", ""), o.optString("type", ""), o.optString("title", ""),
                    o.optString("tv_title", ""), o.optInt("season_number", 0), o.optInt("episode_number", 0),
                    o.optLong("ts", 0), o.optLong("duration", 0), o.optString("poster", ""),
                    o.optInt("poster_width", 0), o.optInt("poster_height", 0), o.optString("parent_guid", ""),
                    o.optString("release_date", ""), o.optString("vote_average", "")));
        }
        return out;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS * 2);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "CleanViddaTV");
        return c;
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("too large");
            }
            return out.toByteArray();
        }
    }
}
