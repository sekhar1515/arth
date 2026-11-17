package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Boots the full application against a real PostgreSQL (Testcontainers) and drives it through HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    /**
     * Database for the whole test run (shared Spring context). Set TEST_DB_URL (plus TEST_DB_USER and
     * TEST_DB_PASSWORD) to use an existing PostgreSQL instead of starting a Testcontainers one, e.g. on
     * machines or CI runners without Docker.
     */
    static final String EXTERNAL_DB = System.getenv("TEST_DB_URL");
    static final PostgreSQLContainer<?> POSTGRES =
            EXTERNAL_DB == null || EXTERNAL_DB.isBlank() ? new PostgreSQLContainer<>("postgres:16-alpine") : null;

    static {
        if (POSTGRES != null) POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "arth"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "arth"));
        }
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;

    /** A registered user with a session cookie. */
    protected record Session(UUID userId, Cookie cookie) {
    }

    protected Session register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.in";
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "correct-horse-42", "displayName", "Test"))))
                .andReturn();
        if (r.getResponse().getStatus() != 200) throw new AssertionError("register failed: " + r.getResponse().getContentAsString());
        Cookie cookie = r.getResponse().getCookie("arth_session");
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return new Session(UUID.fromString(body.get("id").asText()), cookie);
    }

    protected JsonNode call(Session s, MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        if (s != null) req.cookie(s.cookie());
        MvcResult r = mvc.perform(req).andReturn();
        String content = r.getResponse().getContentAsString();
        if (r.getResponse().getStatus() != expectedStatus) {
            throw new AssertionError("Expected " + expectedStatus + " but got " + r.getResponse().getStatus() + ": " + content);
        }
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }

    protected JsonNode getJson(Session s, String url) throws Exception {
        return call(s, get(url), 200);
    }

    protected JsonNode postJson(Session s, String url, Object body, int expected) throws Exception {
        return call(s, post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), expected);
    }

    protected JsonNode putJson(Session s, String url, Object body, int expected) throws Exception {
        return call(s, put(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)), expected);
    }

    protected UUID createAccount(Session s, String name, String type, String last4, String opening) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", name);
        body.put("type", type);
        body.put("last4", last4);
        body.put("openingBalance", opening);
        return UUID.fromString(postJson(s, "/api/accounts", body, 201).get("id").asText());
    }

    /** Manual transaction without a category (so it goes through suggestion + review). */
    protected UUID manual(Session s, UUID account, String direction, String amount, String date, String merchant) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("accountId", account);
        body.put("direction", direction);
        body.put("amount", amount);
        body.put("date", date);
        body.put("time", "12:30:00");
        body.put("merchant", merchant);
        body.put("paymentMethod", "UPI");
        return UUID.fromString(postJson(s, "/api/transactions", body, 201).get("id").asText());
    }

    protected UUID categoryId(Session s, String path) throws Exception {
        for (JsonNode top : getJson(s, "/api/categories")) {
            if (top.get("path").asText().equals(path)) return UUID.fromString(top.get("id").asText());
            for (JsonNode child : top.get("children")) {
                if (child.get("path").asText().equals(path)) return UUID.fromString(child.get("id").asText());
            }
        }
        throw new AssertionError("No category " + path);
    }

    protected JsonNode txn(Session s, UUID id) throws Exception {
        return getJson(s, "/api/transactions/" + id).get("transaction");
    }

    protected JsonNode summary(Session s, String from, String to) throws Exception {
        return getJson(s, "/api/dashboard/summary?from=" + from + "&to=" + to);
    }
}
