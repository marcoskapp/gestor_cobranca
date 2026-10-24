package br.com.gestorcobranca.assistant.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Versão da política do assistente para um escopo: assessoria (credor e carteira nulos), credor ou
 * carteira (a mais específica prevalece). Rascunho editável; publicar torna a versão imutável (também
 * por gatilho no banco), recebe o próximo número de versão do código e aposenta a publicada anterior
 * do mesmo código (um código por escopo). O id da versão publicada é
 * o id do ator {@code ASSISTANT} (auditoria, eventos e alçada no negotiation).
 */
@Entity
@Table(name = "assistant_policy", schema = "assistant")
public class AssistantPolicy extends TenantScopedEntity {

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** Atribuída na publicação (sequência por código); nula enquanto rascunho. */
    @Column(name = "policy_version")
    private Integer policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PolicyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30)
    private AssistantMode mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private String settings;

    @Column(name = "settings_schema_version", nullable = false)
    private int settingsSchemaVersion;

    @Column(name = "prompt_code", nullable = false, length = 40)
    private String promptCode;

    @Column(name = "prompt_version", nullable = false)
    private int promptVersion;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Column(name = "retire_reason", length = 300)
    private String retireReason;

    protected AssistantPolicy() {
    }

    /**
     * @param code código legível (nome do ator {@code assistant:<code>})
     */
    public AssistantPolicy(UUID creditorId, UUID portfolioId, String code, String name, PolicySettings settings,
                           String settingsJson) {
        Guard.require(portfolioId == null || creditorId != null, "assistant_policy.portfolio_requires_creditor",
                "Política de carteira exige o credor da carteira");
        Guard.require(code != null && code.matches("[a-z0-9-]{2,40}"), "assistant_policy.code_invalid",
                "Código: 2 a 40 caracteres minúsculos, dígitos ou hífen");
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.code = code;
        this.status = PolicyStatus.DRAFT;
        rename(name);
        apply(settings, settingsJson);
    }

    public void update(String name, PolicySettings settings, String settingsJson) {
        requireDraft();
        rename(name);
        apply(settings, settingsJson);
    }

    /** @param version próxima versão do código (maior publicada/aposentada + 1) */
    public void publish(Instant now, UUID by, int version) {
        requireDraft();
        Guard.require(version >= 1, "assistant_policy.version_invalid", "Versão inválida");
        this.policyVersion = version;
        this.status = PolicyStatus.PUBLISHED;
        this.publishedAt = now;
        this.publishedBy = by;
    }

    /** Encerra a versão publicada (substituída por nova versão ou desativação explícita). */
    public void retire(Instant now, String reason) {
        Guard.requireState(status == PolicyStatus.PUBLISHED, "assistant_policy.not_published",
                "Somente política publicada pode ser aposentada");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 300, "assistant_policy.reason_required",
                "Motivo obrigatório (até 300 caracteres)");
        this.status = PolicyStatus.RETIRED;
        this.retiredAt = now;
        this.retireReason = reason.trim();
    }

    public boolean sameScope(UUID creditor, UUID portfolio) {
        return java.util.Objects.equals(creditorId, creditor) && java.util.Objects.equals(portfolioId, portfolio);
    }

    /** 0 = assessoria, 1 = credor, 2 = carteira. */
    public int specificity() {
        return portfolioId != null ? 2 : creditorId != null ? 1 : 0;
    }

    private void requireDraft() {
        Guard.requireState(status == PolicyStatus.DRAFT, "assistant_policy.not_draft",
                "Versão publicada é imutável: crie uma nova versão");
    }

    private void rename(String name) {
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 120, "assistant_policy.name_required",
                "Nome obrigatório (até 120 caracteres)");
        this.name = name.trim();
    }

    private void apply(PolicySettings settings, String settingsJson) {
        this.mode = settings.mode();
        this.settings = settingsJson;
        this.settingsSchemaVersion = PolicySettings.SCHEMA_VERSION;
        this.promptCode = settings.promptCode();
        this.promptVersion = settings.promptVersion();
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /** Versão publicada; 0 para rascunho. */
    public int getPolicyVersion() {
        return policyVersion == null ? 0 : policyVersion;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public AssistantMode getMode() {
        return mode;
    }

    public String getSettings() {
        return settings;
    }

    public String getPromptCode() {
        return promptCode;
    }

    public int getPromptVersion() {
        return promptVersion;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getPublishedBy() {
        return publishedBy;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public String getRetireReason() {
        return retireReason;
    }
}
