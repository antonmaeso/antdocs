package com.example.documenter.vcsgateway;

/**
 * Unchecked exception thrown internally by VCS provider HTTP client code when a
 * rate-limit response (HTTP 429) is detected.
 *
 * <p>This exception carries the number of seconds the caller should wait before
 * retrying. It is caught and handled by {@link RateLimitHandler} — callers of
 * {@link VCSProvider} never see this exception.
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    /**
     * Creates a new {@code RateLimitException}.
     *
     * @param message           a human-readable description (e.g. the provider name and endpoint)
     * @param retryAfterSeconds the number of seconds to wait before retrying, as indicated
     *                          by the provider's rate-limit response header
     */
    public RateLimitException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Returns the number of seconds the provider has asked the client to wait
     * before retrying.
     *
     * @return retry-after duration in seconds
     */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
