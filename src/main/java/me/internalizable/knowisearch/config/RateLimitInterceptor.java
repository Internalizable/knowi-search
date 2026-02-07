package me.internalizable.knowisearch.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.internalizable.knowisearch.ratelimit.RateLimitService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    public RateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        boolean allowed;
        String endpoint;

        if (path.startsWith("/api/chat")) {
            endpoint = "chat";
            allowed = rateLimitService.isChatAllowed(clientIp);
        } else if (path.startsWith("/api/ingest")) {
            endpoint = "ingest";
            allowed = rateLimitService.isIngestAllowed(clientIp);
        } else if (path.startsWith("/api/")) {
            endpoint = "api";
            allowed = rateLimitService.isAllowed(clientIp);
        } else {
            return true;
        }

        RateLimitService.RateLimitInfo info = rateLimitService.getRateLimitInfo(clientIp, endpoint);
        response.setHeader("X-RateLimit-Limit", String.valueOf(info.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(info.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(info.resetSeconds()));

        if (!allowed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                    "{\"error\":\"Rate limit exceeded. Please try again in %d seconds.\",\"success\":false}",
                    info.resetSeconds()
            ));
            return false;
        }

        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }

        return request.getRemoteAddr();
    }
}

