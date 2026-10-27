package ua.mk.svitlo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ApiDiagnosticsTest {
    private ServerSocket server;
    private Thread worker;
    private DataHealth health;
    private Field field;
    private Object original;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String retry;

    @Before public void start() throws Exception {
        health = new DataHealth(new MemoryPreferences().preferences);
        field = Api.class.getDeclaredField("health"); field.setAccessible(true);
        original = field.get(null); field.set(null, health);
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    int length = 0;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    for (int i = 0; i < length; i++) input.read();
                    requests.incrementAndGet();
                    String headers = "HTTP/1.1 " + status + " Test\r\nContent-Length: 2\r\nConnection: close\r\n"
                            + (retry == null ? "" : "Retry-After: " + retry + "\r\n") + "\r\n";
                    java.io.OutputStream out = socket.getOutputStream();
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.write("[]".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (java.io.IOException ignored) {}
            }
        });
        worker.setDaemon(true);
        worker.start();
    }
    @After public void stop() throws Exception {
        if (server != null) server.close();
        if (worker != null) worker.join(1000);
        if (field != null) field.set(null, original);
    }
    private String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/"; }

    @Test public void successfulEmptyReplyIsReturnedAsSuccess() throws Exception {
        assertEquals("[]", Api.post(url(), "{}"));
        assertEquals(1, requests.get());
    }
    @Test public void serverCooldownBlocksSecondRequestEvenIfExplicitlyRetried() throws Exception {
        status = 429; retry = "120";
        try { Api.post(url(), "{}"); fail(); }
        catch (Api.Failure e) { assertEquals(429, e.problem.code); assertEquals(DataProblem.HTTP, e.problem.kind); }
        assertTrue(health.pauseUntil(DataHealth.WORKS) > System.currentTimeMillis());
        try { Api.post(url(), "{}"); fail(); }
        catch (Api.Failure e) { assertEquals(DataProblem.PAUSED, e.problem.kind); }
        assertEquals(1, requests.get());
    }
    @Test public void cooldownIsPerOriginAndExpiredCooldownDoesNotBlock() throws Exception {
        health.defer(DataHealth.OFF, 503, System.currentTimeMillis() + 120_000);
        health.defer(DataHealth.WORKS, 429, System.currentTimeMillis() - 1000);
        assertEquals("[]", Api.post(url(), "{}"));
        assertEquals(1, requests.get());
    }
    @Test public void forbiddenResponseKeepsItsCodeWithoutGuessingIpBlock() throws Exception {
        status = 403;
        try { Api.post(url(), "{}"); fail(); }
        catch (Api.Failure e) {
            assertEquals(403, e.problem.code);
            assertEquals("Сервер відхилив запит", e.problem.label());
        }
        assertEquals(0, health.pauseUntil(DataHealth.WORKS));
    }
}
