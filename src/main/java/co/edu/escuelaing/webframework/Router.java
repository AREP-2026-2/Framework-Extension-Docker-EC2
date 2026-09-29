package co.edu.escuelaing.webframework;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a request path to the lambda registered for it. This is the "lobby directory":
 * the only place that knows which handler answers which path, so new routes never
 * require touching the server's connection loop. Thread-safe: worker threads read it
 * concurrently.
 */
public class Router {

    private final Map<String, Service> getRoutes = new ConcurrentHashMap<>();

    public void addGetRoute(String path, Service service) {
        getRoutes.put(path, service);
    }

    /** Returns the registered lambda for the given path, or null if none was registered. */
    public Service findGetRoute(String path) {
        return getRoutes.get(path);
    }
}
