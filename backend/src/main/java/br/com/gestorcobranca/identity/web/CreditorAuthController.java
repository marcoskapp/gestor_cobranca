package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.ActivationStart;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.CreditorClient;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.CreditorLoginResult;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.CreditorMeView;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.LoginThrottledException;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.SiteView;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AuthenticationFailedException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Portal do credor (ADR 0011): autenticação pública (limite por IP em memória e persistido no serviço, Origin
 * obrigatório e igual à própria origem) e a conta autenticada ({@code /me}, saída). O refresh token só vai e volta no
 * cookie {@code HttpOnly} próprio; os tokens de link vão no corpo (nunca no caminho da URL).
 */
@RestController
class CreditorAuthController {

    private final CreditorAuthentication authentication;
    private final LoginRateLimiter rateLimiter;
    private final CreditorRefreshCookie refreshCookie;
    private final AccessLogger accessLogger;
    private final IdentityProperties properties;

    CreditorAuthController(CreditorAuthentication authentication, LoginRateLimiter rateLimiter,
                           CreditorRefreshCookie refreshCookie, AccessLogger accessLogger, IdentityProperties properties) {
        this.authentication = authentication;
        this.rateLimiter = rateLimiter;
        this.refreshCookie = refreshCookie;
        this.accessLogger = accessLogger;
        this.properties = properties;
    }

    @GetMapping(ApiPaths.CREDITOR + "/sites/{slug}")
    SiteView site(@PathVariable String slug, HttpServletRequest http) {
        limit(http, "creditor_site");
        return authentication.site(slug).orElseThrow(() -> new ResourceNotFoundException("site", "?"));
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        guard(http, "creditor_login");
        return respond(authentication.login(request.tenant(), request.email(), request.password(), client(http)));
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/mfa")
    ResponseEntity<LoginResponse> verifyMfa(@Valid @RequestBody MfaRequest request, HttpServletRequest http) {
        guard(http, "creditor_mfa");
        return respond(authentication.verifyMfa(request.stepToken(), request.code(), client(http)));
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/password/expired")
    ResponseEntity<LoginResponse> changeExpiredPassword(@Valid @RequestBody ExpiredPasswordRequest request,
                                                        HttpServletRequest http) {
        guard(http, "creditor_password");
        return respond(authentication.changeExpiredPassword(request.stepToken(), request.newPassword(), client(http)));
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/activation/start")
    ActivationStart startActivation(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        guard(http, "creditor_activation");
        return authentication.startActivation(request.token());
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/activation/complete")
    ResponseEntity<LoginResponse> completeActivation(@Valid @RequestBody ActivationRequest request, HttpServletRequest http) {
        guard(http, "creditor_activation");
        return respond(authentication.completeActivation(request.token(), request.newPassword(), request.code(),
                client(http)));
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/refresh")
    ResponseEntity<LoginResponse> refresh(@CookieValue(name = CreditorRefreshCookie.NAME, required = false) String token,
                                          HttpServletRequest http) {
        guard(http, "creditor_refresh");
        if (token == null) {
            throw AuthenticationFailedException.invalidToken();
        }
        try {
            return respond(authentication.refresh(token, client(http)));
        } catch (AuthenticationFailedException e) {
            throw new AuthController.CookieClearingAuthenticationException(e, refreshCookie.clear());
        }
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        guard(http, "creditor_password");
        authentication.requestPasswordReset(request.tenant(), request.email(), client(http));
    }

    @PostMapping(ApiPaths.CREDITOR + "/auth/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        guard(http, "creditor_password");
        authentication.completePasswordReset(request.token(), request.newPassword(), client(http));
    }

    @GetMapping(ApiPaths.CREDITOR + "/me")
    @PreAuthorize("hasAuthority('ACTOR_CREDITOR')")
    CreditorMeView me() {
        return authentication.me();
    }

    @PostMapping(ApiPaths.CREDITOR + "/session/logout")
    @PreAuthorize("hasAuthority('ACTOR_CREDITOR')")
    ResponseEntity<Void> logout() {
        authentication.logout();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear()).build();
    }

    /** Falha no refresh: responde 401 e remove o cookie inválido (o cliente volta à entrada). */
    @ExceptionHandler(AuthController.CookieClearingAuthenticationException.class)
    ResponseEntity<ProblemDetail> refreshFailed(AuthController.CookieClearingAuthenticationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getCause().getMessage());
        problem.setProperty("code", ((AuthenticationFailedException) e.getCause()).code());
        problem.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.SET_COOKIE, e.clearCookie()).body(problem);
    }

    @ExceptionHandler(LoginThrottledException.class)
    ResponseEntity<ProblemDetail> throttled(LoginThrottledException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
        problem.setProperty("code", "rate_limited");
        problem.setProperty("requestId", MDC.get(MdcKeys.REQUEST_ID));
        long seconds = Math.max(1, Duration.between(Instant.now(), e.until()).toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(problem);
    }

    // ------------------------------------------------------------------

    private ResponseEntity<LoginResponse> respond(CreditorLoginResult result) {
        LoginResponse body = new LoginResponse(result.status().name(), result.accessToken(), result.accessTokenExpiresAt(),
                result.stepToken(), result.me());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.refreshToken() != null) {
            builder.header(HttpHeaders.SET_COOKIE, refreshCookie.create(result.refreshToken(), result.refreshTokenExpiresAt()));
        }
        return builder.body(body);
    }

    /** Limite por IP (por instância) e Origin obrigatório e igual à própria origem (RS-20). */
    private void guard(HttpServletRequest http, String operation) {
        limit(http, operation);
        String origin = http.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || (!origin.equals(AuthController.ownOrigin(http))
                && !properties.cors().allowedOrigins().contains(origin))) {
            accessLogger.log(AccessEvent.anonymous(AccessEventType.ACCESS_DENIED, null, null,
                    Map.of("reason", origin == null ? "creditor_auth_without_origin" : "creditor_auth_untrusted_origin",
                            "operation", operation)));
            throw AuthenticationFailedException.invalidToken();
        }
    }

    private void limit(HttpServletRequest http, String operation) {
        LoginRateLimiter.Decision decision = rateLimiter.acquire(operation + ":" + http.getRemoteAddr());
        if (decision != LoginRateLimiter.Decision.ALLOWED) {
            // só a primeira recusa do minuto vai para o log de acesso (achado B05)
            if (decision == LoginRateLimiter.Decision.REJECTED_FIRST) {
                accessLogger.log(AccessEvent.anonymous(AccessEventType.RATE_LIMITED, null, null, Map.of("operation", operation)));
            }
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde um minuto.");
        }
    }

    private static CreditorClient client(HttpServletRequest http) {
        return new CreditorClient(http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }

    record LoginRequest(@NotBlank @Size(max = 50) String tenant, @NotBlank @Size(max = 200) String email,
                        @NotBlank @Size(max = 128) String password) {
    }

    record MfaRequest(@NotBlank @Size(max = 4096) String stepToken, @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    record ExpiredPasswordRequest(@NotBlank @Size(max = 4096) String stepToken, @NotBlank @Size(max = 128) String newPassword) {
    }

    record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    record ActivationRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 128) String newPassword,
                             @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    record ForgotPasswordRequest(@NotBlank @Size(max = 50) String tenant, @NotBlank @Size(max = 200) String email) {
    }

    record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 128) String newPassword) {
    }

    /** @param status AUTHENTICATED, MFA_REQUIRED ou PASSWORD_CHANGE_REQUIRED */
    record LoginResponse(String status, String accessToken, Instant accessTokenExpiresAt, String stepToken,
                         CreditorMeView me) {
    }
}
