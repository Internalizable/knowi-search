package me.internalizable.knowisearch.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Rate limiting service with configurable tiers for different endpoints.
 *
 * Automatically uses distributed Redis rate limiting if available,
 * falls back to local rate limiting for single-server deployments.
 *
 * Configuration (application.properties):
 * - rate-limit.enabled: Enable/disable rate limiting
 * - rate-limit.use-redis: Use Redis for distributed rate limiting (recommended for production)
 * - rate-limit.requests-per-minute: General API limit per IP
 * - rate-limit.chat-requests-per-minute: Chat endpoint limit (expensive AI calls)
 * - rate-limit.ingest-requests-per-hour: Ingest endpoint limit (very expensive)
 */
@Service
public class RateLimitService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitService.class);

    private final RateLimiter rateLimiter;
    private final boolean enabled;

    @Value("${rate-limit.requests-per-minute:30}")
    private int requestsPerMinute;

    @Value("${rate-limit.chat-requests-per-minute:20}")
    private int chatRequestsPerMinute;

    @Value("${rate-limit.ingest-requests-per-hour:5}")
    private int ingestRequestsPerHour;

    @Value("${rate-limit.global-requests-per-minute:1000}")
    private int globalRequestsPerMinute;

    public RateLimitService(
            RateLimiter rateLimiter,
            @Value("${rate-limit.enabled:true}") boolean enabled) {
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
    }

    /**
     * Check if a general API request is allowed
     */
    public boolean isAllowed(String clientIp) {
        if (!enabled) return true;

        if (!checkGlobalLimit()) {
            return false;
        }

        String key = "api:" + clientIp;

        return rateLimiter.tryConsume(key, requestsPerMinute, 60);
    }

    /**
     * Check if a chat request is allowed (stricter - expensive AI calls)
     */
    public boolean isChatAllowed(String clientIp) {
        if (!enabled) return true;

        if (!checkGlobalLimit()) {
            return false;
        }

        String key = "chat:" + clientIp;

        return rateLimiter.tryConsume(key, chatRequestsPerMinute, 60);
    }

    /**
     * Check if an ingest request is allowed (very strict - expensive crawling)
     */
    public boolean isIngestAllowed(String clientIp) {
        if (!enabled) return true;

        if (!checkGlobalLimit()) {
            return false;
        }

        String key = "ingest:" + clientIp;

        return rateLimiter.tryConsume(key, ingestRequestsPerHour, 3600);
    }

    /**
     * Get rate limit info for HTTP headers
     */
    public RateLimitInfo getRateLimitInfo(String clientIp, String endpoint) {
        if (!enabled) {
            return new RateLimitInfo(999999, 999999, 60);
        }

        return switch (endpoint) {
            case "chat" -> {
                String key = "chat:" + clientIp;
                long remaining = rateLimiter.getRemainingTokens(key, chatRequestsPerMinute, 60);
                yield new RateLimitInfo(chatRequestsPerMinute, remaining, 60);
            }
            case "ingest" -> {
                String key = "ingest:" + clientIp;
                long remaining = rateLimiter.getRemainingTokens(key, ingestRequestsPerHour, 3600);
                yield new RateLimitInfo(ingestRequestsPerHour, remaining, 3600);
            }
            default -> {
                String key = "api:" + clientIp;
                long remaining = rateLimiter.getRemainingTokens(key, requestsPerMinute, 60);
                yield new RateLimitInfo(requestsPerMinute, remaining, 60);
            }
        };
    }

    /**
     * Admin: Reset rate limit for an IP
     */
    public void resetLimit(String clientIp, String endpoint) {
        String key = endpoint + ":" + clientIp;
        rateLimiter.reset(key);
        logger.info("Rate limit reset for IP: {} ({})", clientIp, endpoint);
    }

    private boolean checkGlobalLimit() {
        return rateLimiter.tryConsume("global", globalRequestsPerMinute, 60);
    }

    /**
     * Rate limit info for response headers
     */
    public record RateLimitInfo(int limit, long remaining, int resetSeconds) {}
}

