package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.AuthenticatedUserView;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.ClientInfo;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginCommand;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginResult;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.MfaEnrollment;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * Endpoints públicos do fluxo de autenticação. Todos com limite de tentativas por IP. O refresh
 * token nunca aparece no corpo: vai e volta apenas no cookie {@code HttpOnly}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/auth")
class AuthController {

    private final AuthenticationUseCases authentication;
    private final LoginRateLimiter rateLimiter;
    private final RefreshCookie refreshCookie;
    private final AccessLogger accessLogger;
    private final IdentityProperties properties;

    AuthController(AuthenticationUseCases authentication, LoginRateLimiter rateLimiter, RefreshCookie refreshCookie,
                   AccessLogger accessLogger, IdentityProperties properties) {
        this.authentication = authentication;
        this.rateLimiter = rateLimiter;
        this.refreshCookie = refreshCookie;
        this.accessLogger = accessLogger;
        this.properties = properties;
    }

    @PostMapping("/login")
    ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        limit(http, "login");
        LoginResult result = authentication.login(new LoginCommand(request.tenant(), request.email(), request.password(),
                client(http)));
        return respond(result);
    }

    @PostMapping("/mfa/verify")
    ResponseEntity<LoginResponse> verifyMfa(@Valid @RequestBody MfaRequest request, HttpServletRequest http) {
        limit(http, "mfa");
        return respond(authentication.verifyMfa(request.stepToken(), request.code(), client(http)));
    }

    @PostMapping("/mfa/enrollment")
    MfaEnrollment startEnrollment(@Valid @RequestBody StepRequest request, HttpServletRequest http) {
        limit(http, "mfa");
        return authentication.startMfaEnrollment(request.stepToken());
    }

    @PostMapping("/mfa/enrollment/confirm")
    ResponseEntity<LoginResponse> confirmEnrollment(@Valid @RequestBody MfaRequest request, HttpServletRequest http) {
        limit(http, "mfa");
        return respond(authentication.confirmMfaEnrollment(request.stepToken(), request.code(), client(http)));
    }

    @PostMapping("/password/expired")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeExpiredPassword(@Valid @RequestBody ExpiredPasswordRequest request, HttpServletRequest http) {
        limit(http, "password");
        authentication.changeExpiredPassword(request.stepToken(), request.newPassword());
    }

    @PostMapping("/refresh")
    ResponseEntity<LoginResponse> refresh(@CookieValue(name = RefreshCookie.NAME, required = false) String token,
                                          HttpServletRequest http) {
        limit(http, "refresh");
        requireAllowedOrigin(http);
        if (token == null) {
            throw AuthenticationFailedException.invalidToken();
        }
        try {
            return respond(authentication.refresh(token, client(http)));
        } catch (AuthenticationFailedException e) {
            // sessão inválida: remove o cookie para o cliente voltar ao login
            throw new CookieClearingAuthenticationException(e, refreshCookie.clear());
        }
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        limit(http, "password");
        authentication.requestPasswordReset(request.tenant(), request.email());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
        limit(http, "password");
        authentication.completeCredentialAction(request.token(), request.newPassword());
    }

    /** Falha no refresh: responde 401 e remove o cookie inválido. */
    @org.springframework.web.bind.annotation.ExceptionHandler(CookieClearingAuthenticationException.class)
    ResponseEntity<org.springframework.http.ProblemDetail> refreshFailed(CookieClearingAuthenticationException e) {
        org.springframework.http.ProblemDetail problem = org.springframework.http.ProblemDetail
                .forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getCause().getMessage());
        problem.setProperty("code", ((AuthenticationFailedException) e.getCause()).code());
        problem.setProperty("requestId", org.slf4j.MDC.get(br.com.gestorcobranca.shared.logging.MdcKeys.REQUEST_ID));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, e.clearCookie())
                .body(problem);
    }

    static final class CookieClearingAuthenticationException extends RuntimeException {

        private final transient String clearCookie;

        CookieClearingAuthenticationException(AuthenticationFailedException cause, String clearCookie) {
            super(cause);
            this.clearCookie = clearCookie;
        }

        String clearCookie() {
            return clearCookie;
        }
    }

    // ------------------------------------------------------------------

    private ResponseEntity<LoginResponse> respond(LoginResult result) {
        LoginResponse body = new LoginResponse(result.status().name(), result.accessToken(), result.accessTokenExpiresAt(),
                result.stepToken(), result.user());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.refreshToken() != null) {
            builder.header(HttpHeaders.SET_COOKIE, refreshCookie.create(result.refreshToken(), result.refreshTokenExpiresAt()));
        }
        return builder.body(body);
    }

    private void limit(HttpServletRequest http, String operation) {
        if (!rateLimiter.tryAcquire(operation + ":" + http.getRemoteAddr())) {
            accessLogger.log(AccessEvent.anonymous(AccessEventType.RATE_LIMITED, null, null, Map.of("operation", operation)));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde um minuto.");
        }
    }

    /** Defesa adicional ao SameSite: rejeita refresh disparado por origem não autorizada. */
    private void requireAllowedOrigin(HttpServletRequest http) {
        String origin = http.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.equals(ownOrigin(http)) && !properties.cors().allowedOrigins().contains(origin)) {
            accessLogger.log(AccessEvent.anonymous(AccessEventType.ACCESS_DENIED, null, null,
                    Map.of("reason", "refresh_from_untrusted_origin")));
            throw AuthenticationFailedException.invalidToken();
        }
    }

    /**
     * Origem da própria aplicação (o frontend é servido pelo backend — ADR-0007). Atrás do ALB,
     * esquema e host refletem os cabeçalhos X-Forwarded-* (server.forward-headers-strategy).
     */
    static String ownOrigin(HttpServletRequest http) {
        String scheme = http.getScheme();
        int port = http.getServerPort();
        boolean defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return scheme + "://" + http.getServerName() + (defaultPort ? "" : ":" + port);
    }

    private static ClientInfo client(HttpServletRequest http) {
        return new ClientInfo(http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }

    record LoginRequest(@NotBlank @Size(max = 50) String tenant, @NotBlank @Size(max = 200) String email,
                        @NotBlank @Size(max = 128) String password) {
    }

    record StepRequest(@NotBlank @Size(max = 4096) String stepToken) {
    }

    record MfaRequest(@NotBlank @Size(max = 4096) String stepToken, @NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }

    record ExpiredPasswordRequest(@NotBlank @Size(max = 4096) String stepToken,
                                  @NotBlank @Size(max = 128) String newPassword) {
    }

    record ForgotPasswordRequest(@NotBlank @Size(max = 50) String tenant, @NotBlank @Size(max = 200) String email) {
    }

    record ResetPasswordRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 128) String newPassword) {
    }

    /**
     * @param status      AUTHENTICATED, MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED ou PASSWORD_CHANGE_REQUIRED
     * @param stepToken   presente nas etapas intermediárias
     */
    record LoginResponse(String status, String accessToken, Instant accessTokenExpiresAt, String stepToken,
                         AuthenticatedUserView user) {
    }
}
