package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.AuthenticatedUserView;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.MfaEnrollment;
import br.com.gestorcobranca.identity.application.port.in.MyAccountUseCases;
import br.com.gestorcobranca.identity.application.port.in.SessionUseCases;
import br.com.gestorcobranca.identity.application.port.in.SessionUseCases.SessionView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Conta do usuário autenticado: perfil, senha, MFA, sessões e logout. */
@RestController
@RequestMapping(ApiPaths.V1 + "/me")
@PreAuthorize("hasAuthority('ACTOR_USER')")
class MeController {

    private final MyAccountUseCases myAccount;
    private final SessionUseCases sessions;
    private final AuthenticationUseCases authentication;
    private final RefreshCookie refreshCookie;

    MeController(MyAccountUseCases myAccount, SessionUseCases sessions, AuthenticationUseCases authentication,
                 RefreshCookie refreshCookie) {
        this.myAccount = myAccount;
        this.sessions = sessions;
        this.authentication = authentication;
        this.refreshCookie = refreshCookie;
    }

    @GetMapping
    AuthenticatedUserView profile() {
        return myAccount.profile();
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        myAccount.changePassword(request.currentPassword(), request.newPassword());
    }

    @PostMapping("/mfa/enrollment")
    MfaEnrollment startMfa() {
        return myAccount.startMfaEnrollment();
    }

    @PostMapping("/mfa/enrollment/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmMfa(@Valid @RequestBody MfaCodeRequest request) {
        myAccount.confirmMfaEnrollment(request.code());
    }

    @GetMapping("/sessions")
    List<SessionView> mySessions() {
        return sessions.mySessions();
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeSession(@PathVariable UUID sessionId) {
        sessions.revokeMySession(sessionId);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout() {
        authentication.logout();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie.clear()).build();
    }

    record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                 @NotBlank @Size(max = 128) String newPassword) {
    }

    record MfaCodeRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {
    }
}
