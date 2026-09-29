package co.edu.escuelaing.app;

import static co.edu.escuelaing.webframework.WebFramework.*;

import java.time.LocalDateTime;

/**
 * Example web application built on top of the webframework. It only registers
 * routes and configuration -- it never touches sockets, HTTP parsing, or file I/O.
 */
public class Application {

    public static void main(String[] args) throws Exception {

        String staticFilesPath = System.getenv().getOrDefault("STATIC_FILES_PATH", "/webroot");
        staticfiles(staticFilesPath);

        String environment = System.getenv().getOrDefault("APP_ENV", "development");

        get("/hello", (req, resp) -> {
            String name = req.getValue("name");
            if (name == null || name.isBlank()) {
                name = "world";
            }

            String greetingPrefix = System.getenv().getOrDefault("GREETING_PREFIX", "Hello");
            String language = req.getValue("language");

            String message = greetingPrefix + " " + name;
            if (language != null && !language.isBlank()) {
                message += " (" + language + ")";
            }
            return message;
        });

        get("/pi", (req, resp) -> String.valueOf(Math.PI));

        get("/time", (req, resp) -> {
            resp.setContentType("text/plain");
            return LocalDateTime.now().toString();
        });

        get("/env", (req, resp) -> {
            resp.setContentType("text/plain");
            return "APP_ENV=" + environment;
        });

        // Simulates a slow handler (capped at 10 s) to demonstrate concurrent requests
        // and that graceful shutdown waits for in-flight work.
        get("/slow", (req, resp) -> {
            long millis = 2000;
            String ms = req.getValue("ms");
            if (ms != null && !ms.isBlank()) {
                millis = Math.min(Math.max(Long.parseLong(ms), 0), 10_000);
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Done after " + millis + " ms on " + Thread.currentThread().getName();
        });

        // Development-only lifecycle route: must never be reachable in production.
        if (environment.equals("development")) {
            get("/shutdown", (req, resp) -> {
                stop();
                return "Server will stop after this response.";
            });
        }

        String portValue = System.getenv("PORT");
        int port = (portValue == null || portValue.isBlank()) ? 8080 : Integer.parseInt(portValue);

        System.out.println("Starting server on port " + port + " (APP_ENV=" + environment + ")");
        start(port);
    }
}
