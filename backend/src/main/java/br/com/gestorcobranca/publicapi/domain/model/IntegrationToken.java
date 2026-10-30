package br.com.gestorcobranca.publicapi.domain.model;

import br.com.gestorcobranca.publicapi.domain.service.TokenHasher;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Token de integração da API pública, sempre vinculado a <strong>um</strong> credor da assessoria. Guarda só o hash do
 * segredo ({@link TokenHasher}); o prefixo identifica o token sem revelar o segredo. Revogação é definitiva e imediata;
 * a rotação troca o segredo e mantém o anterior válido por uma carência curta (troca sem parada no sistema do credor).
 */
@Entity
@Table(name = "integration_token", schema = "publicapi")
public class IntegrationToken extends TenantScopedEntity {

    /** Carência máxima do segredo anterior na rotação. */
    public static final Duration MAX_ROTATION_GRACE = Duration.ofHours(24);
    public static final int MIN_RATE_LIMIT = 1;
    public static final int MAX_RATE_LIMIT = 6_000;
    public static final int MAX_ALLOWED_IPS = 20;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "prefix", nullable = false, updatable = false, length = 12)
    private String prefix;

    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "hash_key_id", nullable = false, length = 16)
    private String hashKeyId;

    @Column(name = "previous_secret_hash", length = 64)
    private String previousSecretHash;

    @Column(name = "previous_hash_key_id", length = 16)
    private String previousHashKeyId;

    @Column(name = "previous_valid_until")
    private Instant previousValidUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "integration_token_scope", schema = "publicapi", joinColumns = @JoinColumn(name = "token_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 40)
    private Set<TokenScope> scopes = EnumSet.noneOf(TokenScope.class);

    /** Faixas CIDR permitidas (vazio = qualquer origem). Validadas pela aplicação antes de chegar aqui. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "integration_token_allowed_ip", schema = "publicapi", joinColumns = @JoinColumn(name = "token_id"))
    @Column(name = "cidr", nullable = false, length = 49)
    private Set<String> allowedIps = new LinkedHashSet<>();

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private TokenStatus status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoke_reason", length = 500)
    private String revokeReason;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "rotation_count", nullable = false)
    private int rotationCount;

    /** Atualizados em lote pela medição de consumo (fora do caminho da requisição). */
    @Column(name = "last_used_at", insertable = false, updatable = false)
    private Instant lastUsedAt;

    @Column(name = "last_used_ip", insertable = false, updatable = false, length = 45)
    private String lastUsedIp;

    protected IntegrationToken() {
    }

    public IntegrationToken(UUID creditorId, String name, String description, String prefix, String secretHash,
                            String hashKeyId, Set<TokenScope> scopes, int rateLimitPerMinute, List<String> allowedIps,
                            Instant expiresAt, Instant now) {
        Guard.require(creditorId != null, "integration_token.creditor_required", "Credor obrigatório");
        Guard.require(TokenSecret.isValidPrefix(prefix), "integration_token.prefix_invalid", "Prefixo inválido");
        Guard.require(secretHash != null && secretHash.length() == 64, "integration_token.hash_invalid", "Hash inválido");
        Guard.require(scopes != null && !scopes.isEmpty(), "integration_token.scope_required", "Informe ao menos um escopo");
        Guard.require(expiresAt == null || expiresAt.isAfter(now), "integration_token.expiry_in_past",
                "A expiração precisa ser no futuro");
        this.creditorId = creditorId;
        this.prefix = prefix;
        this.secretHash = secretHash;
        this.hashKeyId = hashKeyId;
        this.scopes = EnumSet.copyOf(scopes);
        this.expiresAt = expiresAt;
        this.status = TokenStatus.ACTIVE;
        updateSettings(name, description, rateLimitPerMinute, allowedIps);
    }

    /** Nome, descrição, limite de taxa e IPs permitidos (escopos e credor não mudam: crie outro token). */
    public void updateSettings(String name, String description, int rateLimitPerMinute, List<String> allowedIps) {
        Guard.require(name != null && !name.isBlank(), "integration_token.name_required", "Nome obrigatório");
        Guard.require(name.strip().length() <= 100, "integration_token.name_too_long", "Nome com até 100 caracteres");
        Guard.require(description == null || description.length() <= 500, "integration_token.description_too_long",
                "Descrição com até 500 caracteres");
        Guard.require(rateLimitPerMinute >= MIN_RATE_LIMIT && rateLimitPerMinute <= MAX_RATE_LIMIT,
                "integration_token.rate_limit_invalid",
                "Limite de requisições por minuto entre %d e %d".formatted(MIN_RATE_LIMIT, MAX_RATE_LIMIT));
        List<String> ips = allowedIps == null ? List.of() : allowedIps;
        Guard.require(ips.size() <= MAX_ALLOWED_IPS, "integration_token.allowed_ips_too_many",
                "Informe até %d faixas de IP".formatted(MAX_ALLOWED_IPS));
        Guard.requireState(status != TokenStatus.REVOKED, "integration_token.revoked", "Token revogado");
        this.name = name.strip();
        this.description = description == null || description.isBlank() ? null : description.strip();
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.allowedIps = new LinkedHashSet<>(ips);
    }

    public TokenState stateAt(Instant now) {
        if (status == TokenStatus.REVOKED) {
            return TokenState.REVOKED;
        }
        return expiresAt != null && !now.isBefore(expiresAt) ? TokenState.EXPIRED : TokenState.ACTIVE;
    }

    /**
     * Confere o segredo apresentado com o atual ou, durante a carência da rotação, com o anterior. Não verifica a
     * situação (ver {@link #stateAt}).
     */
    public boolean matches(TokenSecret presented, TokenHasher hasher, Instant now) {
        if (!prefix.equals(presented.prefix())) {
            return false;
        }
        boolean current = hasher.matches(hashKeyId, secretHash, presented);
        boolean previous = previousSecretHash != null && previousValidUntil != null && now.isBefore(previousValidUntil)
                && hasher.matches(previousHashKeyId, previousSecretHash, presented);
        return current || previous;
    }

    /** Revogação definitiva e imediata (inclusive do segredo anterior em carência). */
    public void revoke(UUID by, String reason, Instant now) {
        Guard.requireState(status != TokenStatus.REVOKED, "integration_token.already_revoked", "Token já revogado");
        Guard.require(reason != null && !reason.isBlank(), "integration_token.reason_required", "Informe o motivo");
        Guard.require(reason.strip().length() <= 500, "integration_token.reason_too_long", "Motivo com até 500 caracteres");
        this.status = TokenStatus.REVOKED;
        this.revokedAt = now;
        this.revokedBy = by;
        this.revokeReason = reason.strip();
        this.previousSecretHash = null;
        this.previousHashKeyId = null;
        this.previousValidUntil = null;
    }

    /**
     * Novo segredo. O anterior continua aceito até {@code now + grace} (zero = invalidado na hora). Uma rotação durante
     * a carência de outra encerra a carência anterior: só existem, no máximo, dois segredos válidos.
     */
    public void rotate(String newSecretHash, String newKeyId, Duration grace, Instant now) {
        Guard.requireState(stateAt(now) == TokenState.ACTIVE, "integration_token.not_active",
                "Só tokens ativos podem ser rotacionados");
        Guard.require(grace != null && !grace.isNegative() && grace.compareTo(MAX_ROTATION_GRACE) <= 0,
                "integration_token.grace_invalid", "Carência entre 0 e 24 horas");
        if (grace.isZero()) {
            this.previousSecretHash = null;
            this.previousHashKeyId = null;
            this.previousValidUntil = null;
        } else {
            this.previousSecretHash = secretHash;
            this.previousHashKeyId = hashKeyId;
            this.previousValidUntil = now.plus(grace);
        }
        this.secretHash = newSecretHash;
        this.hashKeyId = newKeyId;
        this.rotatedAt = now;
        this.rotationCount++;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getDisplayPrefix() {
        return TokenSecret.MARKER + prefix;
    }

    public Set<TokenScope> getScopes() {
        return EnumSet.copyOf(scopes);
    }

    public List<String> getAllowedIps() {
        return List.copyOf(allowedIps);
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public TokenStatus getStatus() {
        return status;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public int getRotationCount() {
        return rotationCount;
    }

    public Instant getPreviousValidUntil() {
        return previousValidUntil;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public String getLastUsedIp() {
        return lastUsedIp;
    }
}
