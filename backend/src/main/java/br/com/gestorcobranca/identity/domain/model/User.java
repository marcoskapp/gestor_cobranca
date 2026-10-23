package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Usuário da assessoria (administrador, gestor, supervisor, operador, financeiro, auditor).
 * Concentra as regras de credenciais: ativação, bloqueio progressivo, MFA e escopo de credores.
 */
@Entity
@Table(name = "app_user", schema = "identity")
public class User extends TenantScopedEntity {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, length = 200)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "lockout_count", nullable = false)
    private int lockoutCount;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mfa_secret_encrypted", length = 255)
    private String mfaSecret;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    @Column(name = "mfa_enrolled_at")
    private Instant mfaEnrolledAt;

    /** Último passo TOTP aceito: impede a reutilização do mesmo código (replay). */
    @Column(name = "mfa_last_step")
    private Long mfaLastStep;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Instante da desativação (desligamento); null enquanto o usuário não está desativado. */
    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "business_unit_id")
    private UUID businessUnitId;

    /** Acesso a todos os credores do tenant (atuais e futuros). */
    @Column(name = "all_creditors", nullable = false)
    private boolean allCreditors;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_creditor_scope", schema = "identity", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "creditor_id", nullable = false)
    private Set<UUID> creditorIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", schema = "identity", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_id", nullable = false)
    private Set<UUID> roleIds = new HashSet<>();

    protected User() {
    }

    public User(String name, String email, UUID businessUnitId) {
        rename(name);
        this.email = normalizeEmail(email);
        this.businessUnitId = businessUnitId;
        this.status = UserStatus.PENDING_ACTIVATION;
    }

    public static String normalizeEmail(String email) {
        Guard.require(email != null && EMAIL.matcher(email.trim()).matches(), "user.email_invalid", "E-mail inválido");
        return email.trim().toLowerCase();
    }

    public void rename(String name) {
        Guard.require(name != null && !name.isBlank(), "user.name_required", "Nome obrigatório");
        this.name = name.trim();
    }

    public void moveToBusinessUnit(UUID businessUnitId) {
        this.businessUnitId = businessUnitId;
    }

    // ---------------------------------------------------------------- credenciais

    /** Define a senha (ativação, troca ou redefinição). A política é validada antes, no serviço. */
    public void definePassword(String newHash, Instant now) {
        Guard.require(newHash != null, "user.password_required", "Senha obrigatória");
        Guard.requireState(status != UserStatus.DISABLED, "user.disabled", "Usuário desativado");
        this.passwordHash = newHash;
        this.passwordChangedAt = now;
        this.mustChangePassword = false;
        if (status == UserStatus.PENDING_ACTIVATION) {
            this.status = UserStatus.ACTIVE;
        }
        unlock();
    }

    public void requirePasswordChange() {
        this.mustChangePassword = true;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public boolean canAuthenticate(Instant now) {
        return status == UserStatus.ACTIVE && passwordHash != null && !isLocked(now);
    }

    /**
     * Registra falha de autenticação e aplica bloqueio progressivo.
     *
     * @return true se a conta foi bloqueada nesta tentativa
     */
    public boolean registerFailedLogin(Instant now, LockoutPolicy policy) {
        failedAttempts++;
        if (failedAttempts >= policy.maxFailedAttempts()) {
            lockedUntil = now.plus(policy.lockDurationFor(lockoutCount));
            lockoutCount++;
            failedAttempts = 0;
            return true;
        }
        return false;
    }

    public void registerSuccessfulLogin(Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        lockoutCount = 0;
        lastLoginAt = now;
    }

    public void unlock() {
        failedAttempts = 0;
        lockedUntil = null;
    }

    public boolean isPasswordExpired(Instant now, java.time.Duration maxAge) {
        return passwordChangedAt != null && maxAge != null && passwordChangedAt.plus(maxAge).isBefore(now);
    }

    // ---------------------------------------------------------------- MFA

    public void startMfaEnrollment(String secret) {
        Guard.requireState(!mfaEnabled, "mfa.already_enabled", "MFA já está ativo");
        this.mfaSecret = secret;
    }

    public void confirmMfaEnrollment(Instant now, long step) {
        Guard.requireState(mfaSecret != null && !mfaEnabled, "mfa.no_pending_enrollment", "Não há cadastro de MFA pendente");
        this.mfaEnabled = true;
        this.mfaEnrolledAt = now;
        this.mfaLastStep = step;
    }

    /** Aceita um passo TOTP somente se for posterior ao último usado. */
    public boolean acceptMfaStep(long step) {
        if (mfaLastStep != null && step <= mfaLastStep) {
            return false;
        }
        this.mfaLastStep = step;
        return true;
    }

    public void resetMfa() {
        this.mfaEnabled = false;
        this.mfaSecret = null;
        this.mfaEnrolledAt = null;
        this.mfaLastStep = null;
    }

    // ---------------------------------------------------------------- status e acesso

    public void disable(Instant now) {
        if (status != UserStatus.DISABLED) {
            this.status = UserStatus.DISABLED;
            this.disabledAt = now;
        }
    }

    public void enable() {
        Guard.requireState(status == UserStatus.DISABLED, "user.not_disabled", "Usuário não está desativado");
        this.status = passwordHash == null ? UserStatus.PENDING_ACTIVATION : UserStatus.ACTIVE;
        this.disabledAt = null;
        unlock();
    }

    public void assignRoles(Collection<UUID> roles) {
        Guard.require(roles != null && !roles.isEmpty(), "user.role_required", "Informe ao menos um perfil");
        this.roleIds.clear();
        this.roleIds.addAll(roles);
    }

    public void defineCreditorScope(boolean all, Collection<UUID> creditors) {
        this.allCreditors = all;
        this.creditorIds.clear();
        if (!all && creditors != null) {
            this.creditorIds.addAll(creditors);
        }
    }

    // ---------------------------------------------------------------- getters

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public Instant getMfaEnrolledAt() {
        return mfaEnrolledAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public UUID getBusinessUnitId() {
        return businessUnitId;
    }

    public boolean isAllCreditors() {
        return allCreditors;
    }

    public Set<UUID> getCreditorIds() {
        return Set.copyOf(creditorIds);
    }

    public Set<UUID> getRoleIds() {
        return Set.copyOf(roleIds);
    }
}
