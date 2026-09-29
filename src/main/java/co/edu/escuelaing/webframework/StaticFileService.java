package co.edu.escuelaing.webframework;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Serves static resources (HTML, CSS, JS, images) bundled on the classpath under
 * the configured static-files root (default: /webroot). Binary-safe: everything
 * is read and returned as raw bytes.
 */
public class StaticFileService {

    private static final String DEFAULT_STATIC_ROOT = "/webroot";

    private String staticFilesRoot = DEFAULT_STATIC_ROOT;

    public void setStaticFilesRoot(String staticFilesRoot) {
        if (staticFilesRoot == null || staticFilesRoot.isBlank()) {
            return;
        }
        this.staticFilesRoot = staticFilesRoot.startsWith("/") ? staticFilesRoot : "/" + staticFilesRoot;
    }

    /**
     * Reads the resource at requestPath (relative to the static-files root).
     * Returns null when the resource does not exist, so callers can fall back to a 404.
     */
    public byte[] resolve(String requestPath) throws IOException {
        String normalizedPath = requestPath.equals("/") ? "/index.html" : requestPath;
        String resourcePath = staticFilesRoot + normalizedPath;

        try (InputStream input = StaticFileService.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                return null;
            }
            return readAllBytes(input);
        }
    }

    public String resolveContentType(String requestPath) {
        String path = requestPath.equals("/") ? "/index.html" : requestPath;
        String lower = path.toLowerCase();

        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
