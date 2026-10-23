package ua.mk.svitlo;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Talks to the public data feed behind https://off.energy.mk.ua (and www.energy.mk.ua) */
final class Api {
    static final String BASE = "https://off.energy.mk.ua";

    private Api() {}

    static String get(String path) throws IOException {
        return request(BASE + path, null);
    }

    /** POST a JSON body to a full URL (the outages list on www.energy.mk.ua). */
    static String post(String url, String json) throws IOException {
        return request(url, json);
    }

    private static String request(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "SvitloWidget/1.0 (Android)");
        try {
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    static JSONArray getArray(String path) throws IOException, JSONException {
        return new JSONArray(get(path));
    }
}
