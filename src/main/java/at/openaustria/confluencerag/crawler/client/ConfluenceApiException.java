package at.openaustria.confluencerag.crawler.client;

public class ConfluenceApiException extends RuntimeException {

    private final int statusCode;

    public ConfluenceApiException(int statusCode, String message) {
        super("Confluence API error (HTTP %d): %s".formatted(statusCode, message));
        this.statusCode = statusCode;
    }

    public ConfluenceApiException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
