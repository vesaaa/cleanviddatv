package io.github.vesaaa.cleanviddatv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The parts of the online update that need no Android: comparing versions and reading the two release lists, the
 * CDN's manifest (cleanviddatv/latest.json or beta.json, written by the v* tag workflow) and GitHub's releases API.
 */
final class Updates {
    static final String CDN = "https://download.aikit.stream/cleanviddatv/";
    static final String GITHUB_RELEASES = "https://api.github.com/repos/vesaaa/cleanviddatv/releases?per_page=10";

    /** One published version and where to fetch it; githubApk is the fallback when the CDN cannot be reached. */
    static final class Release {
        final String version, notes, apk, githubApk, sha256;

        Release(String version, String notes, String apk, String githubApk, String sha256) {
            this.version = version;
            this.notes = notes;
            this.apk = apk;
            this.githubApk = githubApk;
            this.sha256 = sha256;
        }
    }

    private Updates() {
    }

    /** The CDN manifest for a channel: beta.json carries betas and stable releases, latest.json stable ones only. */
    static String manifestUrl(boolean beta) {
        return CDN + (beta ? "beta.json" : "latest.json");
    }

    /** Whether a version name is a pre-release, like 1.0.0-beta.1. */
    static boolean isPrerelease(String version) {
        return version != null && version.indexOf('-') > 0;
    }

    /**
     * Semantic version order: 1.0.0-beta.1 < 1.0.0-beta.2 < 1.0.0 < 1.0.1. A leading "v" is ignored; a part that is
     * not a number compares as text, after the numeric ones.
     */
    static int compare(String a, String b) {
        String[] x = split(a), y = split(b);
        int core = compareDotted(x[0], y[0]);
        if (core != 0) return core;
        if (x[1].isEmpty() || y[1].isEmpty()) return x[1].isEmpty() == y[1].isEmpty() ? 0 : x[1].isEmpty() ? 1 : -1;
        return compareDotted(x[1], y[1]);
    }

    private static String[] split(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        int dash = s.indexOf('-');
        return dash < 0 ? new String[]{s, ""} : new String[]{s.substring(0, dash), s.substring(dash + 1)};
    }

    private static int compareDotted(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            boolean pn = p.matches("\\d+"), qn = q.matches("\\d+");
            int c;
            if (pn && qn) c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            else if (pn != qn) c = pn ? -1 : 1;
            else c = p.toLowerCase(Locale.ROOT).compareTo(q.toLowerCase(Locale.ROOT));
            if (c != 0) return c;
        }
        return 0;
    }

    /** The CDN manifest; null when it is not one. */
    static Release parseManifest(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String version = o.getString("version");
            String apk = o.getString("apk");
            if (version.isEmpty() || !apk.startsWith("https://")) return null;
            return new Release(version, o.optString("notes", ""), apk, o.optString("github_apk", ""),
                    o.optString("sha256", "").toLowerCase(Locale.ROOT));
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * The newest release in GitHub's list that the channel takes (pre-releases only with beta), with its APK. Its
     * checksum is a separate asset, so sha256 holds that asset's URL, prefixed "url:", for the caller to fetch.
     */
    static Release parseGithub(String json, boolean beta) {
        try {
            JSONArray list = new JSONArray(json);
            Release best = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                if (r.optBoolean("draft") || (r.optBoolean("prerelease") && !beta)) continue;
                String version = r.optString("tag_name", "");
                if (version.startsWith("v")) version = version.substring(1);
                String apk = null, sum = "";
                JSONArray assets = r.optJSONArray("assets");
                for (int j = 0; assets != null && j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    String name = a.optString("name", ""), url = a.optString("browser_download_url", "");
                    if (name.endsWith(".apk")) apk = url;
                    else if (name.endsWith(".apk.sha256")) sum = "url:" + url;
                }
                if (version.isEmpty() || apk == null || !apk.startsWith("https://")) continue;
                if (best == null || compare(version, best.version) > 0) {
                    best = new Release(version, r.optString("body", ""), apk, apk, sum);
                }
            }
            return best;
        } catch (JSONException e) {
            return null;
        }
    }

    /** The first word of a sha256sum line, lower case; empty when it is not a SHA-256. */
    static String checksum(String sha256sumLine) {
        String s = sha256sumLine == null ? "" : sha256sumLine.trim();
        int space = s.indexOf(' ');
        if (space > 0) s = s.substring(0, space);
        s = s.toLowerCase(Locale.ROOT);
        return s.matches("[0-9a-f]{64}") ? s : "";
    }
}
