package com.expensetracker.privacy;

import com.expensetracker.auth.CurrentUser;
import com.expensetracker.auth.JwtCookieAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {
    private final PrivacyService service;
    private final boolean secureCookie;

    public PrivacyController(PrivacyService service, @Value("${app.security.cookie-secure:true}") boolean secureCookie) {
        this.service = service;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("arth-export-" + LocalDate.now() + ".json").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.export(CurrentUser.id()));
    }

    @GetMapping("/deleted-imports")
    public Map<String, Integer> deletedImports() {
        return Map.of("count", service.deletedImportsCount(CurrentUser.id()));
    }

    /** Lets rows you deleted come back the next time you import the same statement. */
    @DeleteMapping("/deleted-imports")
    public Map<String, Integer> forgetDeletedImports() {
        return Map.of("cleared", service.forgetDeletedImports(CurrentUser.id()));
    }

    public record DeleteAccountRequest(String password, String confirmation) {
    }

    @PostMapping("/delete-account")
    public ResponseEntity<Void> deleteAccount(@RequestBody DeleteAccountRequest req) {
        service.deleteAccount(CurrentUser.id(), req.password(), req.confirmation());
        ResponseCookie clear = ResponseCookie.from(JwtCookieAuthFilter.COOKIE_NAME, "").httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/").maxAge(0).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clear.toString()).build();
    }
}
