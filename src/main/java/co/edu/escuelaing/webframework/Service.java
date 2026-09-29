package co.edu.escuelaing.webframework;

/**
 * Contract implemented by the lambda passed to {@link WebFramework#get}.
 */
@FunctionalInterface
public interface Service {
    String handle(Request request, Response response);
}
