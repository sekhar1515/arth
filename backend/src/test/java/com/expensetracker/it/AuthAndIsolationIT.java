package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AuthAndIsolationIT extends IntegrationTestBase {

    @Test
    void registrationSetsSecureSessionCookieAndSeedsDefaults() throws Exception {
        String email = "c-" + UUID.randomUUID() + "@example.in";
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "correct-horse-42", "displayName", "C")))).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertFalse(r.getResponse().getContentAsString().contains("password"));

        Session s = new Session(null, r.getResponse().getCookie("arth_session"));
        assertEquals(email, getJson(s, "/api/auth/me").get("email").asText());
        assertTrue(getJson(s, "/api/categories").size() >= 20, "default categories seeded");
        assertEquals("CASH", getJson(s, "/api/accounts").get(0).get("type").asText());
        assertEquals("ALWAYS_ASK", getJson(s, "/api/preferences").get("categorisationMode").asText());
    }

    @Test
    void unauthenticatedRequestsGetJson401() throws Exception {
        JsonNode body = call(null, get("/api/transactions"), 401);
        assertEquals("UNAUTHENTICATED", body.get("error").get("code").asText());
    }

    @Test
    void wrongPasswordDuplicateEmailAndWeakPasswordAreRejected() throws Exception {
        String email = "d-" + UUID.randomUUID() + "@example.in";
        postJson(null, "/api/auth/register", Map.of("email", email, "password", "correct-horse-42", "displayName", "D"), 200);
        postJson(null, "/api/auth/register", Map.of("email", email.toUpperCase(), "password", "correct-horse-42", "displayName", "D"), 409);
        postJson(null, "/api/auth/login", Map.of("email", email, "password", "wrong-password-1"), 401);
        postJson(null, "/api/auth/register", Map.of("email", "w-" + email, "password", "short", "displayName", "W"), 400);
        postJson(null, "/api/auth/register", Map.of("email", "x-" + email, "password", "onlyletterslong", "displayName", "X"), 400);
        postJson(null, "/api/auth/login", Map.of("email", email, "password", "correct-horse-42"), 200);
    }

    @Test
    void loginIsRateLimited() throws Exception {
        String email = "rl-" + UUID.randomUUID() + "@example.in";
        postJson(null, "/api/auth/register", Map.of("email", email, "password", "correct-horse-42", "displayName", "R"), 200);
        for (int i = 0; i < 8; i++) postJson(null, "/api/auth/login", Map.of("email", email, "password", "nope-nope-1"), 401);
        postJson(null, "/api/auth/login", Map.of("email", email, "password", "correct-horse-42"), 429);
    }

    @Test
    void usersCannotSeeOrTouchEachOthersData() throws Exception {
        Session a = register();
        Session b = register();
        UUID accA = createAccount(a, "A bank", "BANK", "1111", "1000.00");
        UUID tA = manual(a, accA, "DEBIT", "450.00", "2026-10-05", "SWIGGY");
        UUID food = categoryId(b, "Food & Dining > Food Delivery");

        call(b, get("/api/transactions/" + tA), 404);
        postJson(b, "/api/transactions/" + tA + "/categorise", Map.of("categoryId", food), 404);
        assertEquals(0, getJson(b, "/api/transactions").get("totalElements").asLong());
        var body = new java.util.HashMap<String, Object>();
        body.put("accountId", accA);
        body.put("direction", "DEBIT");
        body.put("amount", "10.00");
        body.put("date", "2026-10-05");
        postJson(b, "/api/transactions", body, 404);
        // B's category cannot be used on A's transaction either
        postJson(a, "/api/transactions/" + tA + "/categorise", Map.of("categoryId", food), 404);
        assertEquals(1, getJson(a, "/api/transactions").get("totalElements").asLong());
    }
}
