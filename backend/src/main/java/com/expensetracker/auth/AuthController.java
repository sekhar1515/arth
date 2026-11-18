package com.expensetracker.auth;

import com.expensetracker.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(@NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 10, max = 72) String password,
                                  @NotBlank @Size(max = 100) String displayName,
                                  @Size(max = 200) String inviteCode) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String code) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    public record CodeRequest(String code) {
    }

    public record DisableMfaRequest(String password, String code) {
    }

    public record ForgotPasswordRequest(@NotBlank @Size(max = 254) String email) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 72) String newPassword) {
    }

    private final AuthService authService;
    private final RegistrationPolicy registration;
    private final PasswordResetService passwordReset;
    private final com.expensetracker.networth.PriceRefresher priceRefresher;
    private final com.expensetracker.auth.mfa.MfaService mfa;
    private final JwtService jwtService;
    private final boolean secureCookie;
    private final boolean demoEnabled;
    private final String demoEmail;
    private final String demoPassword;

    public AuthController(AuthService authService, RegistrationPolicy registration, PasswordResetService passwordReset, com.expensetracker.networth.PriceRefresher priceRefresher, com.expensetracker.auth.mfa.MfaService mfa, JwtService jwtService,
                          @Value("${app.security.cookie-secure:true}") boolean secureCookie,
                          @Value("${app.demo.enabled:false}") boolean demoEnabled,
                          @Value("${app.demo.email:demo@arth.local}") String demoEmail,
                          @Value("${app.demo.password:}") String demoPassword) {
        this.authService = authService;
        this.registration = registration;
        this.passwordReset = passwordReset;
        this.priceRefresher = priceRefresher;
        this.mfa = mfa;
        this.jwtService = jwtService;
        this.secureCookie = secureCookie;
        this.demoEnabled = demoEnabled;
        this.demoEmail = demoEmail;
        this.demoPassword = demoPassword;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.UserDto> register(@Valid @RequestBody RegisterRequest req, jakarta.servlet.http.HttpServletRequest http) {
        registration.check(http.getRemoteAddr(), req.inviteCode());
        User user = authService.register(req.email(), req.password(), req.displayName());
        return withSession(user);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthService.UserDto> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        User user = authService.login(req.email(), req.password(), req.code(), http.getRemoteAddr());
        priceRefresher.refreshIfStale("sign-in");
        return withSession(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    @GetMapping("/me")
    public AuthService.UserDto me() {
        return AuthService.UserDto.of(authService.get(CurrentUser.id()));
    }

    @PostMapping("/password")
    public ResponseEntity<AuthService.UserDto> changePassword(@RequestBody PasswordChange req) {
        // other devices are signed out; this one gets a new session so you stay signed in here
        return withSession(authService.changePassword(CurrentUser.id(), req.currentPassword(), req.newPassword()));
    }

    @GetMapping("/mfa")
    public com.expensetracker.auth.mfa.MfaService.StatusDto mfaStatus() {
        return mfa.status(CurrentUser.id());
    }

    @PostMapping("/mfa/setup")
    public com.expensetracker.auth.mfa.MfaService.SetupDto mfaSetup() {
        return mfa.begin(CurrentUser.id());
    }

    @PostMapping("/mfa/enable")
    public Map<String, Object> mfaEnable(@RequestBody CodeRequest req) {
        return Map.of("recoveryCodes", mfa.enable(CurrentUser.id(), req.code()));
    }

    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> mfaDisable(@RequestBody DisableMfaRequest req) {
        mfa.disable(CurrentUser.id(), req.password(), req.code());
        return ResponseEntity.noContent().build();
    }

    /** Lets the sign-up page show an invite-code field, or say sign-ups are closed; tells the forgot-password page whether e-mail works. */
    @GetMapping("/registration-info")
    public Map<String, Object> registrationInfo() {
        return Map.of("mode", registration.mode().name(), "emailAvailable", passwordReset.emailAvailable());
    }

    /** Always 202, whether or not the account exists, so this can't be used to find out who has an account. */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest req, jakarta.servlet.http.HttpServletRequest http) {
        passwordReset.request(req.email(), http.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordReset.reset(req.token(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    /** Lets the login page offer the demo account only when the server runs in demo mode. */
    @GetMapping("/demo-info")
    public Map<String, Object> demoInfo() {
        if (!demoEnabled) return Map.of("enabled", false);
        return Map.of("enabled", true, "email", demoEmail, "password", demoPassword);
    }

    private ResponseEntity<AuthService.UserDto> withSession(User user) {
        String token = jwtService.issue(user.getId(), user.getEmail());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(token, jwtService.ttl()).toString())
                .body(AuthService.UserDto.of(user));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(JwtCookieAuthFilter.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
