package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.MfaEnrollment;
import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.PasswordHistoryEntry;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.CredentialRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.identity.domain.service.OpaqueToken;
import br.com.gestorcobranca.identity.domain.service.PasswordPolicy;
import br.com.gestorcobranca.identity.domain.service.Totp;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Regras de credenciais compartilhadas pelos fluxos de autenticação, conta própria e
 * administração: política e histórico de senha, links de ativação/redefinição e cadastro de MFA.
 * Executa sempre na transação (e no tenant) do chamador.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CredentialService {

    private static final Duration ACTIVATION_TTL = Duration.ofHours(72);
    private static final Duration RESET_TTL = Duration.ofHours(1);

    private final UserRepository users;
    private final CredentialRepository credentials;
    private final SessionManager sessionManager;
    private final PasswordEncoder passwordEncoder;
    private final IdentityMailer mailer;
    private final AuditTrail auditTrail;
    private final IdentityProperties properties;
    private final PasswordPolicy passwordPolicy;
    private final String baseUrl;
    private final Clock clock;

    CredentialService(UserRepository users, CredentialRepository credentials, SessionManager sessionManager,
                      PasswordEncoder passwordEncoder, IdentityMailer mailer, AuditTrail auditTrail,
                      IdentityProperties properties, @Value("${app.base-url}") String baseUrl, Clock clock) {
        this.users = users;
        this.credentials = credentials;
        this.sessionManager = sessionManager;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.passwordPolicy = new PasswordPolicy(properties.password().minLength());
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    /** Valida política e histórico, grava a nova senha e registra no histórico. */
    void changePassword(User user, String newPassword) {
        passwordPolicy.validate(newPassword, user.getEmail(), user.getName());
        if (user.getPasswordHash() != null && passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("password.reused", "A nova senha deve ser diferente da atual");
        }
        for (PasswordHistoryEntry entry : credentials.findRecentHistory(user.getId(), properties.password().history())) {
            if (passwordEncoder.matches(newPassword, entry.getPasswordHash())) {
                throw new BusinessRuleException("password.reused",
                        "A senha não pode repetir as últimas %d senhas".formatted(properties.password().history()));
            }
        }
        if (user.getPasswordHash() != null) {
            credentials.saveHistory(new PasswordHistoryEntry(user.getId(), user.getPasswordHash()));
        }
        user.definePassword(passwordEncoder.encode(newPassword), Instant.now(clock));
        users.save(user);
        auditTrail.record(AuditEntry.of("user.password_changed", "user", user.getId()));
    }

    /** Gera link de ativação ou redefinição, invalida os anteriores e envia por e-mail. */
    void issueCredentialLink(User user, ActionToken.Purpose purpose, TenantView tenant) {
        Instant now = Instant.now(clock);
        credentials.findUsableActionTokens(user.getId(), purpose).forEach(previous -> {
            previous.invalidate();
            credentials.saveActionToken(previous);
        });
        OpaqueToken token = OpaqueToken.generate(user.getTenantId());
        Instant expiresAt = now.plus(purpose == ActionToken.Purpose.ACTIVATION ? ACTIVATION_TTL : RESET_TTL);
        credentials.saveActionToken(new ActionToken(user.getId(), purpose, token.hash(), expiresAt));
        String link = credentialLink(baseUrl, purpose, token.value());
        if (purpose == ActionToken.Purpose.ACTIVATION) {
            mailer.sendActivation(user.getEmail(), user.getName(), tenant.tradeName(), link, expiresAt);
        } else {
            mailer.sendPasswordReset(user.getEmail(), user.getName(), link, expiresAt);
        }
        auditTrail.record(AuditEntry.of("user.credential_link_issued", "user", user.getId())
                .meta("purpose", purpose).meta("expiresAt", expiresAt));
    }

    /**
     * Link do e-mail com o token no <strong>fragmento</strong> ({@code #token=}): o navegador não o envia ao
     * servidor, então ele não aparece nos access logs do ALB, em proxies nem no {@code Referer}. A tela lê o
     * fragmento e envia o token no corpo do {@code POST /auth/password/reset}.
     */
    static String credentialLink(String baseUrl, ActionToken.Purpose purpose, String token) {
        String path = purpose == ActionToken.Purpose.ACTIVATION ? "/ativar-conta" : "/redefinir-senha";
        return baseUrl + path + "#token=" + token;
    }

    /** Conclui ativação/redefinição: define a senha e encerra todas as sessões existentes. */
    CompletedAction completeAction(OpaqueToken presented, String newPassword) {
        Instant now = Instant.now(clock);
        ActionToken token = credentials.findActionTokenByHashForUpdate(presented.hash())
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> new BusinessRuleException("credential_link.invalid", "Link inválido ou expirado"));
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new BusinessRuleException("credential_link.invalid", "Link inválido ou expirado"));
        changePassword(user, newPassword);
        token.markUsed(now);
        credentials.saveActionToken(token);
        sessionManager.revokeAll(user.getId(), "password_" + token.getPurpose().name().toLowerCase());
        return new CompletedAction(user.getId(), token.getPurpose());
    }

    MfaEnrollment beginMfaEnrollment(User user) {
        if (user.isMfaEnabled()) {
            throw new BusinessRuleException("mfa.already_enabled", "MFA já está ativo");
        }
        String secret = Totp.generateSecret();
        user.startMfaEnrollment(secret);
        users.save(user);
        return new MfaEnrollment(secret, Totp.otpAuthUri(properties.mfa().issuer(), user.getEmail(), secret));
    }

    boolean completeMfaEnrollment(User user, String code) {
        if (user.getMfaSecret() == null || user.isMfaEnabled()) {
            throw new BusinessRuleException("mfa.no_pending_enrollment", "Inicie o cadastro do autenticador");
        }
        Instant now = Instant.now(clock);
        OptionalLong step = Totp.verify(user.getMfaSecret(), code, now);
        if (step.isEmpty()) {
            return false;
        }
        user.confirmMfaEnrollment(now, step.getAsLong());
        users.save(user);
        auditTrail.record(AuditEntry.of("user.mfa_enrolled", "user", user.getId()));
        return true;
    }

    record CompletedAction(UUID userId, ActionToken.Purpose purpose) {
    }
}
