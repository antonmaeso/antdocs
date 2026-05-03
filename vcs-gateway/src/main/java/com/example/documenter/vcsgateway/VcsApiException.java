package com.example.documenter.vcsgateway;

/**
 * Unchecked exception thrown by {@link VCSProvider} implementations when a VCS API call fails.
 *
 * <p>Callers should expect this exception from every {@code VCSProvider} method and handle it
 * according to the method's documented error contract (e.g. log-and-continue, propagate, or
 * rely on Temporal activity retry).
 *
 * <p>Rate-limit responses (HTTP 429) are handled internally by provider implementations and
 * are never surfaced as {@code VcsApiException}.
 */
public class VcsApiException extends RuntimeException {

    private final int httpStatus;

    /**
     * Creates a new {@code VcsApiException}.
     *
     * @param message    a human-readable description of the failure
     * @param httpStatus the HTTP status code returned by the VCS API, or {@code 0} if not applicable
     */
    public VcsApiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /**
     * Creates a new {@code VcsApiException} with a cause.
     *
     * @param message    a human-readable description of the failure
     * @param httpStatus the HTTP status code returned by the VCS API, or {@code 0} if not applicable
     * @param cause      the underlying cause
     */
    public VcsApiException(String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the HTTP status code from the failed VCS API call.
     *
     * @return the HTTP status code, or {@code 0} if the failure was not HTTP-related
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
