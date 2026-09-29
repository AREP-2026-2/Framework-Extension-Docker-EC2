package co.edu.escuelaing.webframework;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The "building entrance and receptionist": accepts connections and hands each one
 * to a pool of worker threads, which parse the request line and either call the
 * router or the static file service. Several requests are therefore served at the
 * same time.
 *
 * Shutdown is graceful: {@link #stop()} closes the listening socket (so no new
 * connections are accepted), then the worker pool is allowed to finish every
 * request already in progress before the server returns from {@link #start()}.
 */
public class HttpServer {

    /** Maximum time a client may take to send its request before the worker gives up on it. */
    private static final int CLIENT_READ_TIMEOUT_MS = 10_000;

    private final int port;
    private final int threadPoolSize;
    private final long shutdownTimeoutSeconds;
    private final Router router;
    private final StaticFileService staticFileService;

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch terminated = new CountDownLatch(1);

    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;
    private ExecutorService workers;

    public HttpServer(int port, int threadPoolSize, long shutdownTimeoutSeconds,
                      Router router, StaticFileService staticFileService) {
        if (threadPoolSize < 1) {
            throw new IllegalArgumentException("threadPoolSize must be at least 1");
        }
        this.port = port;
        this.threadPoolSize = threadPoolSize;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.router = router;
        this.staticFileService = staticFileService;
    }

    /**
     * Binds the port and serves requests until {@link #stop()} is called. Blocks the
     * calling thread; returns only after in-flight requests have been completed.
     */
    public void start() throws IOException {
        workers = Executors.newFixedThreadPool(threadPoolSize, namedThreads());

        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(port));
            serverSocket = socket;
            running = true;
            started.countDown();
            System.out.println("Server listening on port " + getPort() + " with " + threadPoolSize + " worker threads");

            while (running) {
                Socket clientSocket;
                try {
                    clientSocket = socket.accept();
                } catch (SocketException e) {
                    // accept() is interrupted by stop() closing the socket: that is the normal exit path.
                    if (running) {
                        System.err.println("Error accepting connection: " + e.getMessage());
                    }
                    continue;
                }

                try {
                    workers.execute(() -> handleConnection(clientSocket));
                } catch (RejectedExecutionException e) {
                    clientSocket.close();
                }
            }
        } finally {
            running = false;
            started.countDown();
            awaitInFlightRequests();
            terminated.countDown();
        }
    }

    /**
     * Stops accepting new connections. Requests already being handled still receive
     * their response; {@link #start()} returns once they have all finished.
     * Safe to call more than once and from any thread.
     */
    public void stop() {
        running = false;
        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println("Error closing server socket: " + e.getMessage());
            }
        }
    }

    /** Blocks until the server has bound its port (or failed to), up to the given timeout. */
    public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
        return started.await(timeout, unit);
    }

    /** Blocks until {@link #start()} has finished draining in-flight requests, up to the given timeout. */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return terminated.await(timeout, unit);
    }

    public boolean isRunning() {
        return running;
    }

    /** The port actually bound; useful when the server was created with port 0. */
    public int getPort() {
        ServerSocket socket = serverSocket;
        return socket != null ? socket.getLocalPort() : port;
    }

    private void awaitInFlightRequests() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                System.err.println("Some requests did not finish within " + shutdownTimeoutSeconds
                        + "s; forcing shutdown.");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Server stopped gracefully.");
    }

    private static java.util.concurrent.ThreadFactory namedThreads() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> new Thread(runnable, "http-worker-" + counter.getAndIncrement());
    }

    private void handleConnection(Socket clientSocket) {
        try (Socket socket = clientSocket) {
            socket.setSoTimeout(CLIENT_READ_TIMEOUT_MS);
            handleRequest(socket);
        } catch (IOException e) {
            System.err.println("Error handling connection: " + e.getMessage());
        }
    }

    private void handleRequest(Socket clientSocket) throws IOException {
        try (
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = clientSocket.getOutputStream()
        ) {
            String requestLine = in.readLine();

            if (requestLine == null || requestLine.isBlank()) {
                writeResponse(out, 400, "text/plain", bytes("400 Bad Request"));
                return;
            }

            consumeHeaders(in);

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeResponse(out, 400, "text/plain", bytes("400 Bad Request: malformed request line"));
                return;
            }

            String method = parts[0];
            String fullPath = parts[1];
            System.out.println("[" + Thread.currentThread().getName() + "] " + method + " " + fullPath);

            if (!"GET".equals(method)) {
                writeResponse(out, 405, "text/plain", bytes("405 Method Not Allowed"));
                return;
            }

            String path;
            Map<String, String> queryParams = new HashMap<>();
            int queryIndex = fullPath.indexOf('?');
            if (queryIndex >= 0) {
                path = fullPath.substring(0, queryIndex);
                parseQueryParams(fullPath.substring(queryIndex + 1), queryParams);
            } else {
                path = fullPath;
            }

            dispatch(path, method, queryParams, out);

        } catch (IOException e) {
            System.err.println("I/O error while handling request: " + e.getMessage());
        }
    }

    private void dispatch(String path, String method, Map<String, String> queryParams, OutputStream out)
            throws IOException {
        Service service = router.findGetRoute(path);

        if (service != null) {
            Request request = new Request(method, path, queryParams);
            Response response = new Response();
            String body = invokeSafely(service, request, response);
            writeResponse(out, response.getStatusCode(), response.getContentType(), bytes(body));
            return;
        }

        byte[] fileBytes;
        try {
            fileBytes = staticFileService.resolve(path);
        } catch (IOException e) {
            writeResponse(out, 500, "text/plain", bytes("500 Internal Server Error"));
            return;
        }

        if (fileBytes != null) {
            writeResponse(out, 200, staticFileService.resolveContentType(path), fileBytes);
            return;
        }

        writeResponse(out, 404, "text/plain", bytes("404 Not Found"));
    }

    private static String invokeSafely(Service service, Request request, Response response) {
        try {
            String result = service.handle(request, response);
            return result == null ? "" : result;
        } catch (RuntimeException e) {
            response.setStatusCode(500);
            response.setContentType("text/plain");
            return "500 Internal Server Error: " + e.getMessage();
        }
    }

    private static void consumeHeaders(BufferedReader in) throws IOException {
        String header;
        while ((header = in.readLine()) != null && !header.isBlank()) {
            // Headers are not needed by this lab's routes; just drain them.
        }
    }

    private static void parseQueryParams(String query, Map<String, String> queryParams) {
        if (query == null || query.isBlank()) {
            return;
        }
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    queryParams.put(key, value);
                } else {
                    queryParams.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                }
            } catch (IllegalArgumentException e) {
                // Malformed percent-encoding in a single param must not fail the request.
            }
        }
    }

    private static void writeResponse(OutputStream out, int statusCode, String contentType, byte[] body)
            throws IOException {
        String headers = "HTTP/1.1 " + statusCode + " " + statusText(statusCode) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String statusText(int statusCode) {
        return switch (statusCode) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "";
        };
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
