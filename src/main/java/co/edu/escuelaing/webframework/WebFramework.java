package co.edu.escuelaing.webframework;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Public entry point of the framework. Application developers only ever talk to
 * this class ({@code staticfiles}, {@code get}, {@code start}, {@code stop}) --
 * they never touch sockets, threads, the router, or the static-file resolver directly.
 */
public final class WebFramework {

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_THREAD_POOL_SIZE = 10;
    /** Below Docker's default 10 s grace period, so the drain finishes before SIGKILL. */
    private static final int DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 8;

    private static final Router router = new Router();
    private static final StaticFileService staticFileService = new StaticFileService();

    private static volatile HttpServer server;

    private WebFramework() {
    }

    /** Configures the classpath location that serves static resources, e.g. "/webroot". */
    public static void staticfiles(String location) {
        staticFileService.setStaticFilesRoot(location);
    }

    /** Registers a GET route and the lambda that answers it. */
    public static void get(String path, Service service) {
        router.addGetRoute(path, service);
    }

    /** Starts the server on the port given by the PORT environment variable, defaulting to 8080. */
    public static void start() throws IOException {
        start(envInt("PORT", DEFAULT_PORT));
    }

    /**
     * Starts the server and blocks until it has been stopped and every in-flight
     * request has been answered. The worker pool size and shutdown timeout come
     * from THREAD_POOL_SIZE and SHUTDOWN_TIMEOUT_SECONDS.
     */
    public static void start(int port) throws IOException {
        int threads = envInt("THREAD_POOL_SIZE", DEFAULT_THREAD_POOL_SIZE);
        int shutdownTimeout = envInt("SHUTDOWN_TIMEOUT_SECONDS", DEFAULT_SHUTDOWN_TIMEOUT_SECONDS);

        HttpServer httpServer = new HttpServer(port, threads, shutdownTimeout, router, staticFileService);
        server = httpServer;

        // SIGTERM (docker stop, systemctl stop) and Ctrl+C run shutdown hooks: stop accepting
        // connections and keep the JVM alive until the in-flight requests have been answered.
        Thread shutdownHook = new Thread(() -> {
            System.out.println("Shutdown signal received, stopping server...");
            httpServer.stop();
            try {
                httpServer.awaitTermination(shutdownTimeout + 1L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        httpServer.start();

        // Stopped via stop() rather than a signal: the hook is no longer needed.
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down; the hook is running.
        }
    }

    /** Requests a graceful shutdown: no new connections, in-flight requests still get their response. */
    public static void stop() {
        HttpServer httpServer = server;
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    private static int envInt(String name, int defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : Integer.parseInt(value.trim());
    }
}
