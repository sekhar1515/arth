package com.expensetracker.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Authenticates requests from the httpOnly session cookie (or a Bearer token for API clients). */
public class JwtCookieAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "arth_session";

    private final JwtService jwtService;

    public JwtCookieAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extract(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwtService.verify(token).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    private static String extract(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7);
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE_NAME.equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }
}
