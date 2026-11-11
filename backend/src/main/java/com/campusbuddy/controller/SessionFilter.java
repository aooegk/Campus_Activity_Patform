package com.campusbuddy.controller;

import com.campusbuddy.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.Set;

@Component
public class SessionFilter extends OncePerRequestFilter {
    static final String SESSION_COOKIE = "campus_session";
    static final String CSRF_COOKIE = "campus_csrf";
    static final String ACTOR = "campusActor";
    static final String TOKEN = "campusToken";
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/login", "/api/register", "/api/health");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final AuthService auth;

    public SessionFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/api/") || PUBLIC_PATHS.contains(path)
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        String authorization = request.getHeader("Authorization");
        var cookie = WebUtils.getCookie(request, SESSION_COOKIE);
        boolean cookieAuth = authorization == null && cookie != null;
        String token = cookieAuth ? cookie.getValue() :
                authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        AuthService.Principal principal = auth.authenticate(token);
        if (principal != null) {
            if (cookieAuth && !SAFE_METHODS.contains(request.getMethod())
                    && !auth.csrfMatches(principal, request.getHeader("X-CSRF-Token"))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"ok\":false,\"error\":\"会话校验失败，请刷新页面后重试\"}");
                return;
            }
            request.setAttribute(ACTOR, principal.userId());
            request.setAttribute(TOKEN, token);
        }
        chain.doFilter(request, response);
    }
}
