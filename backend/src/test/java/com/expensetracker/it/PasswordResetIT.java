package com.expensetracker.it;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(OutputCaptureExtension.class)
class PasswordResetIT extends IntegrationTestBase {

    private String emailOf(Session s) throws Exception {
        return getJson(s, "/api/auth/me").get("email").asText();
    }

    private String linkToken(CapturedOutput out) {
        Matcher m = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)").matcher(out.getAll());
        String last = null;
        while (m.find()) last = m.group(1);
        assertNotNull(last, "reset link logged when e-mail isn't configured");
        return last;
    }

    @Test
    void forgotPasswordResetsAndSignsOutEverySession(CapturedOutput out) throws Exception {
        Session s = register();
        String email = emailOf(s);
        // unknown e-mails get the same answer, so the endpoint doesn't reveal who has an account
        postJson(null, "/api/auth/forgot-password", Map.of("email", "nobody-" + email), 202);
        postJson(null, "/api/auth/forgot-password", Map.of("email", email.toUpperCase()), 202);
        String token = linkToken(out);

        postJson(null, "/api/auth/reset-password", Map.of("token", token, "newPassword", "short"), 400);
        postJson(null, "/api/auth/reset-password", Map.of("token", "made-up-token", "newPassword", "brand-new-pass-77"), 400);
        postJson(null, "/api/auth/reset-password", Map.of("token", token, "newPassword", "brand-new-pass-77"), 204);

        call(s, get("/api/auth/me"), 401); // the session from before the reset no longer works
        postJson(null, "/api/auth/reset-password", Map.of("token", token, "newPassword", "another-pass-88"), 400); // single use

        postJson(null, "/api/auth/login", Map.of("email", email, "password", "correct-horse-42"), 401);
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", "brand-new-pass-77")))).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        Cookie fresh = r.getResponse().getCookie("arth_session");
        assertEquals(200, mvc.perform(get("/api/auth/me").cookie(fresh)).andReturn().getResponse().getStatus());
    }

    @Test
    void aNewLinkReplacesTheOldOne(CapturedOutput out) throws Exception {
        Session s = register();
        String email = emailOf(s);
        postJson(null, "/api/auth/forgot-password", Map.of("email", email), 202);
        String first = linkToken(out);
        postJson(null, "/api/auth/forgot-password", Map.of("email", email), 202);
        String second = linkToken(out);
        assertNotEquals(first, second);
        postJson(null, "/api/auth/reset-password", Map.of("token", first, "newPassword", "brand-new-pass-77"), 400);
        postJson(null, "/api/auth/reset-password", Map.of("token", second, "newPassword", "brand-new-pass-77"), 204);
    }

    @Test
    void changingPasswordKeepsThisDeviceSignedInButEndsOthers() throws Exception {
        Session s = register();
        MvcResult r = mvc.perform(post("/api/auth/password").cookie(s.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", "correct-horse-42", "newPassword", "brand-new-pass-77")))).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        Cookie renewed = r.getResponse().getCookie("arth_session");
        assertNotNull(renewed);
        call(s, get("/api/auth/me"), 401);
        assertEquals(200, mvc.perform(get("/api/auth/me").cookie(renewed)).andReturn().getResponse().getStatus());
    }

    @Test
    void registrationInfoSaysWhetherEmailWorks() throws Exception {
        JsonNode info = getJson(null, "/api/auth/registration-info");
        assertFalse(info.get("emailAvailable").asBoolean());
    }
}
