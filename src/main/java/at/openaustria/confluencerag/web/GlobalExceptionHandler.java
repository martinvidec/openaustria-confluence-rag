package at.openaustria.confluencerag.web;

import at.openaustria.confluencerag.crawler.client.ConfluenceApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConfluenceApiException.class)
    public ResponseEntity<ErrorResponse> handleConfluenceError(ConfluenceApiException e) {
        log.error("Confluence API Fehler: {}", e.getMessage());
        return ResponseEntity.status(502).body(
                new ErrorResponse("CONFLUENCE_ERROR", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(
                new ErrorResponse("BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        log.error("Unerwarteter Fehler", e);
        return ResponseEntity.status(500).body(
                new ErrorResponse("INTERNAL_ERROR", "Ein interner Fehler ist aufgetreten"));
    }
}
