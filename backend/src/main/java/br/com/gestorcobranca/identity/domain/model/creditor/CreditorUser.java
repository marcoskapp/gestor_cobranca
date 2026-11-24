package br.com.gestorcobranca.identity.domain.model.creditor;

import br.com.gestorcobranca.identity.domain.model.LockoutPolicy;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Pessoa do credor no portal do credor (ADR 0011): uma conta por credor, convidada pela assessoria. A ativação define
 * senha e MFA juntos — conta ativa sempre tem os dois (o MFA do credor é obrigatório, nunca decidido por perfil). Um
 * contador único para senha e código (zera só ao abrir a sessão) e bloqueio progressivo, como o usuário interno.
 */
@Entity
@Table(name = "creditor_user", schema = "identity")
public class CreditorUser extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, updatable = false, length = 200)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private CreditorRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditorUserStatus status;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mfa_secret_encrypted", length = 255)
    private String mfaSecret;

    @Column(name = "mfa_enrolled_at")
    private Instant mfaEnrolledAt;

    /** Último passo TOTP aceito (login e nova verificação): impede a reutilização do mesmo código. */
    @Column(name = "mfa_last_step")
    private Long mfaLastStep;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "lockout_count", nullable = false)
    private int lockoutCount;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "invited_at", nullable = false)
    private Instant invitedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    protected CreditorUser() {
    }

    public CreditorUser(UUID creditorId, String name, String email, CreditorRole role, UUID invitedBy, Instant now) {
        Guard.require(creditorId != null, "creditor_user.creditor_required", "Credor obrigatório");
        Guard.require(role != null, "creditor_user.role_required", "Perfil obrigatório");
        rename(name);
        this.creditorId = creditorId;
        this.email = User.normalizeEmail(email);
        this.role = role;
        this.status = CreditorUserStatus.INVITED;
        this.invitedBy = invitedBy;
        this.invitedAt = now;
    }

    public void rename(String name) {
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 150, "creditor_user.name_required",
                "Nome obrigatório (até 150 caracteres)");
        this.name = name.trim();
    }

    public void changeRole(CreditorRole role) {
        Guard.require(role != null, "creditor_user.role_required", "Perfil obrigatório");
        Guard.requireState(status != CreditorUserStatus.REVOKED, "creditor_user.revoked", "Acesso revogado");
        this.role = role;
    }

    // ---------------------------------------------------------------- ativação (senha + MFA juntos)

    /** Começa a ativação: gera (ou troca) o segredo do autenticador ainda não confirmado. */
    public void startActivation(String secret) {
        Guard.requireState(status == CreditorUserStatus.INVITED, "creditor_user.not_invited", "Convite inválido");
        this.mfaSecret = secret;
    }

    /** Conclui a ativação: senha (já validada e com hash) e o primeiro passo TOTP aceito, na mesma transação. */
    public void activate(String passwordHash, long mfaStep, Instant now) {
        Guard.requireState(status == CreditorUserStatus.INVITED && mfaSecret != null, "creditor_user.not_invited",
                "Convite inválido");
        Guard.require(passwordHash != null, "creditor_user.password_required", "Senha obrigatória");
        this.passwordHash = passwordHash;
        this.passwordChangedAt = now;
        this.mfaEnrolledAt = now;
        this.mfaLastStep = mfaStep;
        this.status = CreditorUserStatus.ACTIVE;
        unlock();
    }

    /**
     * Volta a conta para o convite (a assessoria refaz o acesso: senha e autenticador novos). Usado quando a pessoa
     * perde o autenticador — o novo cadastro de MFA nunca é feito só com a senha.
     */
    public void resetToInvitation(UUID by, Instant now) {
        Guard.requireState(status != CreditorUserStatus.REVOKED, "creditor_user.revoked", "Acesso revogado");
        this.status = CreditorUserStatus.INVITED;
        this.passwordHash = null;
        this.passwordChangedAt = null;
        this.mfaSecret = null;
        this.mfaEnrolledAt = null;
        this.mfaLastStep = null;
        this.invitedBy = by;
        this.invitedAt = now;
        unlock();
    }

    // ---------------------------------------------------------------- credenciais

    /**
     * Nova senha (redefinição ou senha vencida). <b>Não</b> zera o contador nem o bloqueio: quem controla o e-mail não
     * pode redefinir a senha para ganhar novas tentativas do código do autenticador — só o login completo (senha + MFA)
     * zera.
     */
    public void definePassword(String newHash, Instant now) {
        Guard.requireState(status == CreditorUserStatus.ACTIVE, "creditor_user.not_active", "Conta inativa");
        Guard.require(newHash != null, "creditor_user.password_required", "Senha obrigatória");
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
    }

    public void rehashPassword(String newHash) {
        this.passwordHash = newHash;
    }

    public boolean isPasswordExpired(Instant now, Duration maxAge) {
        return passwordChangedAt != null && maxAge != null && passwordChangedAt.plus(maxAge).isBefore(now);
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean canAuthenticate(Instant now) {
        return status == CreditorUserStatus.ACTIVE && passwordHash != null && mfaEnrolledAt != null && !isLocked(now);
    }

    /**
     * Falha de senha, de código no login ou na nova verificação: um contador só (acertar a senha não zera).
     *
     * @return true se a conta foi bloqueada nesta tentativa
     */
    public boolean registerFailure(Instant now, LockoutPolicy policy) {
        failedAttempts++;
        if (failedAttempts >= policy.maxFailedAttempts()) {
            lockedUntil = now.plus(policy.lockDurationFor(lockoutCount));
            lockoutCount++;
            failedAttempts = 0;
            return true;
        }
        return false;
    }

    /** Sessão aberta: zera o contador e o histórico de bloqueios. */
    public void registerSuccessfulLogin(Instant now) {
        unlock();
        lockoutCount = 0;
        lastLoginAt = now;
    }

    /** Nova verificação aceita: zera as falhas (sem mexer no último acesso). */
    public void registerSuccessfulVerification() {
        failedAttempts = 0;
    }

    /** Aceita um passo TOTP somente se for posterior ao último usado (login e nova verificação compartilham). */
    public boolean acceptMfaStep(long step) {
        if (mfaLastStep != null && step <= mfaLastStep) {
            return false;
        }
        this.mfaLastStep = step;
        return true;
    }

    private void unlock() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public void revoke(UUID by, String reason, Instant now) {
        Guard.require(reason != null && !reason.isBlank() && reason.trim().length() <= 200, "creditor_user.reason_required",
                "Informe o motivo (até 200 caracteres)");
        if (status != CreditorUserStatus.REVOKED) {
            this.status = CreditorUserStatus.REVOKED;
            this.revokedAt = now;
            this.revokedBy = by;
            this.revokeReason = reason.trim();
            this.mfaSecret = null;
            this.passwordHash = null;
        }
    }

    // ---------------------------------------------------------------- getters

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public CreditorRole getRole() {
        return role;
    }

    public CreditorUserStatus getStatus() {
        return status;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public Instant getMfaEnrolledAt() {
        return mfaEnrolledAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }
}
