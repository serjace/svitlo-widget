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
    private static volatile android.content.Context app;
    private static volatile DataHealth health;

    static void initialize(android.content.Context context) {
        app = context.getApplicationContext();
        health = new DataHealth(app.getSharedPreferences("svitlo", android.content.Context.MODE_PRIVATE));
    }

    static final class Failure extends IOException {
        final DataProblem problem;
        Failure(DataProblem problem) { super(problem.kind + (problem.code > 0 ? " HTTP " + problem.code : "")); this.problem = problem; }
    }

    private Api() {}

    static String get(String path) throws IOException {
        return request(BASE + path, null);
    }

    /** POST a JSON body to a full URL (the outages list on www.energy.mk.ua). */
    static String post(String url, String json) throws IOException {
        return request(url, json);
    }

    private static String request(String url, String json) throws IOException {
        String origin = url.startsWith(BASE + "/") ? DataHealth.OFF : DataHealth.WORKS;
        DataHealth diagnostics = health;
        long now = System.currentTimeMillis();
        if (diagnostics != null && diagnostics.pauseUntil(origin) > now) {
            throw new Failure(new DataProblem(DataProblem.PAUSED, diagnostics.pauseCode(origin), diagnostics.pauseUntil(origin)));
        }
        if (app != null) {
            android.net.ConnectivityManager manager = app.getSystemService(android.net.ConnectivityManager.class);
            if (manager != null && manager.getActiveNetwork() == null) {
                throw new Failure(new DataProblem(DataProblem.OFFLINE, 0, 0));
            }
        }
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
            if (code != 200) {
                long retryAt = DataProblem.retryAt(c.getHeaderField("Retry-After"), System.currentTimeMillis());
                if (diagnostics != null && retryAt > System.currentTimeMillis()) diagnostics.defer(origin, code, retryAt);
                InputStream err = c.getErrorStream();
                if (err != null) err.close();
                throw new Failure(new DataProblem(DataProblem.HTTP, code, retryAt));
            }
            // Read to the end and close the stream, but don't disconnect(): Android then keeps
            // the connection open for the next request to the same site, which saves setting up
            // a new secure connection every time (slow and costly on mobile data).
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (IOException e) {
            c.disconnect();   // a broken connection must not be used again
            throw e;
        }
    }

    static JSONArray getArray(String path) throws IOException, JSONException {
        return new JSONArray(get(path));
    }
}
