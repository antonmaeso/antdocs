package com.example.documenter.vcsgateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Utility that wraps a VCS API call with a single rate-limit retry.
 *
 * <p>If the supplied action throws {@link RateLimitException}, the handler sleeps for the
 * indicated {@code retryAfterSeconds} duration and retries exactly once. If the retry also
 * throws {@link RateLimitException}, the exception is wrapped in a {@link VcsApiException}
 * and re-thrown so that callers see a normal API error rather than a rate-limit detail.
 *
 * <p>This class is used internally by all three provider implementations
 * ({@code GitHubProvider}, {@code GitLabProvider}, {@code BitbucketProvider}) to avoid
 * duplicating sleep/retry logic.
 */
public final class RateLimitHandler {

    private static final Logger log = LoggerFactory.getLogger(RateLimitHandler.class);

    private RateLimitHandler() {
        // utility class — not instantiable
    }

    /**
     * Executes the given supplier. If it throws {@link RateLimitException}, sleeps for the
     * indicated duration and retries exactly once. If the retry also throws
     * {@link RateLimitException}, wraps it in a {@link VcsApiException} and throws.
     *
     * @param action the VCS API call to execute
     * @param <T>    the return type of the action
     * @return the result of the action
     * @throws VcsApiException if the retry also hits a rate limit
     */
    public static <T> T executeWithRateLimitRetry(Supplier<T> action) {
        try {
            return action.get();
        } catch (RateLimitException e) {
            sleep(e.getRetryAfterSeconds());
            try {
                return action.get();
            } catch (RateLimitException retryException) {
                throw new VcsApiException(
                        "Rate limit exceeded after retry: " + retryException.getMessage(),
                        429,
                        retryException
                );
            }
        }
    }

    /**
     * Executes the given runnable. If it throws {@link RateLimitException}, sleeps for the
     * indicated duration and retries exactly once. If the retry also throws
     * {@link RateLimitException}, wraps it in a {@link VcsApiException} and throws.
     *
     * @param action the VCS API call to execute
     * @throws VcsApiException if the retry also hits a rate limit
     */
    public static void executeWithRateLimitRetry(Runnable action) {
        try {
            action.run();
        } catch (RateLimitException e) {
            sleep(e.getRetryAfterSeconds());
            try {
                action.run();
            } catch (RateLimitException retryException) {
                throw new VcsApiException(
                        "Rate limit exceeded after retry: " + retryException.getMessage(),
                        429,
                        retryException
                );
            }
        }
    }

    private static void sleep(long seconds) {
        log.info("Rate limit hit — sleeping for {} second(s) before retry", seconds);
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new VcsApiException("Interrupted while waiting for rate-limit retry", 429, ie);
        }
    }
}
