package co.edu.escuelaing.webframework;

/**
 * Lets a lambda handler influence the HTTP response (status code and content type)
 * without any knowledge of sockets or raw HTTP framing.
 */
public class Response {

    private int statusCode = 200;
    private String contentType = "text/plain";

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
