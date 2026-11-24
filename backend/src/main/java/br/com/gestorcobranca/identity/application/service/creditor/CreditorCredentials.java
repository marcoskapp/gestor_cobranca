package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.identity.application.CreditorPortalProperties;
import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorActionToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorPasswordHistoryEntry;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import br.com.gestorcobranca.identity.domain.service.OpaqueToken;
import br.com.gestorcobranca.identity.domain.service.PasswordPolicy;
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
import java.time.Instant;
import java.util.Optional;

/**
 * Credenciais da pessoa do credor: política e histórico de senha e os links de convite e redefinição. Link sempre com
 * o token no <strong>fragmento</strong> ({@code #token=}) e montado com {@code app.base-url} (nunca com o Host da
 * requisição); só o hash é guardado; emitir um novo invalida os pendentes da conta.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CreditorCredentials {

    private final CreditorAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final IdentityMailer mailer;
    private final AuditTrail auditTrail;
    private final IdentityProperties identityProperties;
    private final CreditorPortalProperties properties;
    private final PasswordPolicy passwordPolicy;
    private final String baseUrl;
    private final Clock clock;

    CreditorCredentials(CreditorAccountRepository accounts, PasswordEncoder passwordEncoder, IdentityMailer mailer,
                        AuditTrail auditTrail, IdentityProperties identityProperties, CreditorPortalProperties properties,
                        @Value("${app.base-url}") String baseUrl, Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.auditTrail = auditTrail;
        this.identityProperties = identityProperties;
        this.properties = properties;
        this.passwordPolicy = new PasswordPolicy(identityProperties.password().minLength());
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    /** Valida a política e devolve o hash (a ativação ainda não tem senha anterior). */
    String hashNewPassword(CreditorUser user, String newPassword) {
        passwordPolicy.validate(newPassword, user.getEmail(), user.getName());
        if (user.getPasswordHash() != null && passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("password.reused", "A nova senha deve ser diferente da atual");
        }
        int history = identityProperties.password().history();
        for (CreditorPasswordHistoryEntry entry : accounts.findRecentHistory(user.getId(), history)) {
            if (passwordEncoder.matches(newPassword, entry.getPasswordHash())) {
                throw new BusinessRuleException("password.reused",
                        "A senha não pode repetir as últimas %d senhas".formatted(history));
            }
        }
        return passwordEncoder.encode(newPassword);
    }

    /** Troca de senha de conta ativa (redefinição ou vencida): guarda a anterior no histórico. */
    void changePassword(CreditorUser user, String newPassword) {
        String hash = hashNewPassword(user, newPassword);
        if (user.getPasswordHash() != null) {
            accounts.saveHistory(new CreditorPasswordHistoryEntry(user.getId(), user.getPasswordHash()));
        }
        user.definePassword(hash, Instant.now(clock));
        accounts.saveUser(user);
        auditTrail.record(AuditEntry.of("creditor_user.password_changed", "creditor_user", user.getId()));
    }

    /** Novo convite (invalida os links pendentes) e e-mail depois do commit. */
    Instant issueInvitation(CreditorUser user, TenantView tenant, String creditorName) {
        String token = issue(user, CreditorActionToken.Purpose.INVITATION, properties.invitationTtl());
        Instant expiresAt = Instant.now(clock).plus(properties.invitationTtl());
        mailer.sendCreditorInvitation(user.getEmail(), user.getName(), tenant.tradeName(), creditorName,
                link(tenant, "ativar", token), expiresAt);
        auditTrail.record(AuditEntry.of("creditor_user.invitation_issued", "creditor_user", user.getId())
                .meta("expiresAt", expiresAt));
        return expiresAt;
    }

    void issuePasswordReset(CreditorUser user, TenantView tenant) {
        String token = issue(user, CreditorActionToken.Purpose.PASSWORD_RESET, properties.passwordResetTtl());
        Instant expiresAt = Instant.now(clock).plus(properties.passwordResetTtl());
        mailer.sendCreditorPasswordReset(user.getEmail(), user.getName(), tenant.tradeName(),
                link(tenant, "redefinir-senha", token), expiresAt);
        auditTrail.record(AuditEntry.of("creditor_user.password_reset_issued", "creditor_user", user.getId())
                .meta("expiresAt", expiresAt));
    }

    /** Há link de redefinição ainda válido emitido há menos de {@code interval}? */
    boolean hasRecentPasswordReset(CreditorUser user, java.time.Duration interval) {
        Instant now = Instant.now(clock);
        return accounts.findUsableActionTokens(user.getId()).stream()
                .anyMatch(t -> t.getPurpose() == CreditorActionToken.Purpose.PASSWORD_RESET && t.isUsable(now)
                        && t.getCreatedAt() != null && t.getCreatedAt().isAfter(now.minus(interval)));
    }

    /** Invalida todos os links pendentes da conta (revogação, acesso refeito, portal desativado). */
    void invalidateLinks(CreditorUser user) {
        accounts.findUsableActionTokens(user.getId()).forEach(previous -> {
            previous.invalidate();
            accounts.saveActionToken(previous);
        });
    }

    /** Link de uso único ainda válido, com o propósito esperado (lido com lock). */
    Optional<CreditorActionToken> usableToken(OpaqueToken presented, CreditorActionToken.Purpose purpose) {
        Instant now = Instant.now(clock);
        return accounts.findActionTokenByHashForUpdate(presented.hash())
                .filter(t -> t.getPurpose() == purpose && t.isUsable(now));
    }

    private String issue(CreditorUser user, CreditorActionToken.Purpose purpose, java.time.Duration ttl) {
        invalidateLinks(user);
        OpaqueToken token = OpaqueToken.generate(user.getTenantId());
        accounts.saveActionToken(new CreditorActionToken(user.getId(), purpose, token.hash(), Instant.now(clock).plus(ttl)));
        return token.value();
    }

    private String link(TenantView tenant, String page, String token) {
        return baseUrl + "/credor/" + tenant.slug() + "/" + page + "#token=" + token;
    }
}
