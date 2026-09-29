package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpServerTest {

    private static final long SLOW_MS = 1000;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final CountDownLatch slowRequestStarted = new CountDownLatch(1);
    private HttpServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws Exception {
        Router router = new Router();
        router.addGetRoute("/ping", (req, resp) -> "pong");
        router.addGetRoute("/slow", (req, resp) -> {
            slowRequestStarted.countDown();
            try {
                Thread.sleep(SLOW_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow done";
        });

        server = new HttpServer(0, 8, 5, router, new StaticFileService());
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        serverThread.start();
        assertTrue(server.awaitStarted(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        serverThread.join(10_000);
    }

    @Test
    void servesSlowRequestsInParallel() {
        int requests = 5;
        long begin = System.nanoTime();

        List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            responses.add(client.sendAsync(get("/slow"), HttpResponse.BodyHandlers.ofString()));
        }
        responses.forEach(future -> assertEquals(200, future.join().statusCode()));

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        // Sequentially this would take requests * SLOW_MS; in parallel it is close to SLOW_MS.
        assertTrue(elapsedMs < 2 * SLOW_MS, "5 slow requests took " + elapsedMs + " ms");
    }

    @Test
    void fastRequestIsNotBlockedBySlowOne() throws Exception {
        CompletableFuture<HttpResponse<String>> slow =
                client.sendAsync(get("/slow"), HttpResponse.BodyHandlers.ofString());
        assertTrue(slowRequestStarted.await(5, TimeUnit.SECONDS));

        long begin = System.nanoTime();
        HttpResponse<String> fast = client.send(get("/ping"), HttpResponse.BodyHandlers.ofString());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertEquals("pong", fast.body());
        assertTrue(elapsedMs < SLOW_MS / 2, "fast request waited " + elapsedMs + " ms");
        assertEquals(200, slow.join().statusCode());
    }

    @Test
    void gracefulShutdownFinishesInFlightRequestAndRejectsNewOnes() throws Exception {
        CompletableFuture<HttpResponse<String>> inFlight =
                client.sendAsync(get("/slow"), HttpResponse.BodyHandlers.ofString());
        assertTrue(slowRequestStarted.await(5, TimeUnit.SECONDS));

        server.stop();

        assertThrows(ConnectException.class,
                () -> client.send(get("/ping"), HttpResponse.BodyHandlers.ofString()));

        HttpResponse<String> response = inFlight.join();
        assertEquals(200, response.statusCode());
        assertEquals("slow done", response.body());

        assertTrue(server.awaitTermination(5, TimeUnit.SECONDS));
        assertFalse(server.isRunning());
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
    }
}
