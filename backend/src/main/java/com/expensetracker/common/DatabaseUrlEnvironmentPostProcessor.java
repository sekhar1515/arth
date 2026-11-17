package com.expensetracker.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Accepts a DATABASE_URL in the form hosted Postgres providers hand out (Neon, Supabase, Render, Railway…):
 * {@code postgresql://user:password@host:5432/dbname?sslmode=require}, and turns it into Spring's JDBC settings.
 * Explicit DB_URL / spring.datasource.url still wins.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String raw = env.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank() || env.getProperty("DB_URL") != null) return;
        Map<String, Object> props = toJdbc(raw.trim());
        if (!props.isEmpty()) env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", props));
    }

    static Map<String, Object> toJdbc(String raw) {
        Map<String, Object> out = new HashMap<>();
        if (raw.startsWith("jdbc:")) {
            out.put("spring.datasource.url", raw);
            return out;
        }
        if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) return out;
        URI uri = URI.create(raw.replaceFirst("^postgres(ql)?://", "postgresql://"));
        StringBuilder url = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) url.append(':').append(uri.getPort());
        url.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/postgres" : uri.getRawPath());
        String query = uri.getRawQuery();
        if (query != null && !query.isBlank()) {
            // libpq-only options the JDBC driver rejects
            String kept = String.join("&", java.util.Arrays.stream(query.split("&"))
                    .filter(p -> !p.startsWith("channel_binding=")).toList());
            if (!kept.isBlank()) url.append('?').append(kept);
        }
        out.put("spring.datasource.url", url.toString());
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            out.put("spring.datasource.username", decode(colon >= 0 ? userInfo.substring(0, colon) : userInfo));
            if (colon >= 0) out.put("spring.datasource.password", decode(userInfo.substring(colon + 1)));
        }
        return out;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
