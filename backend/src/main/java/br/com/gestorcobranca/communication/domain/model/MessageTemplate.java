package br.com.gestorcobranca.communication.domain.model;

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
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Versão de um template de mensagem de um credor ({@code creditorId} nulo = assessoria). O par
 * (credor, código) tem versões numeradas; somente uma publicada por vez, e versões publicadas são
 * imutáveis (alterar = nova versão). As variáveis tipadas ficam em JSON ({@code variables}) e são
 * validadas por {@code TemplateRenderer}.
 * <p>
 * WhatsApp: o provedor exige template aprovado para iniciar contato (fora da janela de 24 h). A
 * aprovação é registrada aqui (nome no provedor, referência, decisão); editar um rascunho de
 * WhatsApp volta a aprovação para {@code PENDING}; publicar exige {@code APPROVED}.
 */
@Entity
@Table(name = "message_template", schema = "communication")
public class MessageTemplate extends TenantScopedEntity {

    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
    public static final int BODY_MAX = 8000;
    /** Limite de corpo de template do WhatsApp Cloud API. */
    public static final int WHATSAPP_BODY_MAX = 1024;
    public static final int SMS_BODY_MAX = 480;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "code", nullable = false, length = 60, updatable = false)
    private String code;

    @Column(name = "template_version", nullable = false, updatable = false)
    private int templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private ChannelType channel;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private TemplatePurpose purpose;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "body", nullable = false, length = BODY_MAX)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false)
    private String variables;

    @Column(name = "language", nullable = false, length = 10)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TemplateStatus status;

    @Column(name = "provider_template_name", length = 120)
    private String providerTemplateName;

    /** Categoria no provedor (só WhatsApp; nulo nos demais canais). */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_category", length = 20)
    private ProviderCategory providerCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 20)
    private ApprovalStatus approvalStatus;

    @Column(name = "approval_reference", length = 120)
    private String approvalReference;

    @Column(name = "approval_note", length = 500)
    private String approvalNote;

    @Column(name = "approval_decided_at")
    private Instant approvalDecidedAt;

    @Column(name = "approval_registered_by")
    private UUID approvalRegisteredBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "retired_at")
    private Instant retiredAt;

    protected MessageTemplate() {
    }

    /** Rascunho da versão {@code templateVersion} do código. Conteúdo já validado (variáveis). */
    public MessageTemplate(UUID creditorId, String code, int templateVersion, ChannelType channel, Content content) {
        Guard.require(code != null && CODE.matcher(code).matches(), "template.code_invalid",
                "Código em maiúsculas, números e _ (2 a 60)");
        Guard.require(templateVersion >= 1, "template.version_invalid", "Versão inválida");
        Guard.require(channel != null, "template.channel_required", "Canal obrigatório");
        this.creditorId = creditorId;
        this.code = code;
        this.templateVersion = templateVersion;
        this.channel = channel;
        this.status = TemplateStatus.DRAFT;
        this.approvalStatus = channel == ChannelType.WHATSAPP ? ApprovalStatus.PENDING : ApprovalStatus.NOT_REQUIRED;
        apply(content);
    }

    /**
     * Conteúdo editável de um rascunho.
     *
     * @param variablesJson lista de {@link TemplateVariable} em JSON
     * @param language      idioma no provedor (ex.: {@code pt_BR})
     * @param providerCategory categoria no provedor (WhatsApp; nula = {@code UTILITY}); proibida nos demais canais
     */
    public record Content(String name, TemplatePurpose purpose, String subject, String body, String variablesJson,
                          String language, String providerTemplateName, ProviderCategory providerCategory) {
    }

    public void edit(Content content) {
        Guard.requireState(status == TemplateStatus.DRAFT, "template.not_draft",
                "Somente rascunhos podem ser alterados; crie uma nova versão");
        apply(content);
        if (channel == ChannelType.WHATSAPP) {
            // conteúdo novo precisa de nova aprovação do provedor
            this.approvalStatus = ApprovalStatus.PENDING;
            this.approvalReference = null;
            this.approvalNote = null;
            this.approvalDecidedAt = null;
            this.approvalRegisteredBy = null;
        }
    }

    private void apply(Content c) {
        Guard.require(c != null, "template.content_required", "Conteúdo obrigatório");
        Guard.require(c.name() != null && !c.name().isBlank() && c.name().length() <= 120, "template.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(c.purpose() != null, "template.purpose_required", "Finalidade obrigatória");
        Guard.require(c.body() != null && !c.body().isBlank(), "template.body_required", "Corpo obrigatório");
        int max = switch (channel) {
            case WHATSAPP -> WHATSAPP_BODY_MAX;
            case SMS -> SMS_BODY_MAX;
            case EMAIL -> BODY_MAX;
        };
        Guard.require(c.body().length() <= max, "template.body_too_long",
                "Corpo com no máximo %d caracteres no canal %s".formatted(max, channel));
        if (channel == ChannelType.EMAIL) {
            Guard.require(c.subject() != null && !c.subject().isBlank() && c.subject().length() <= 300,
                    "template.subject_required", "Assunto obrigatório no e-mail (até 300 caracteres)");
        } else {
            Guard.require(c.subject() == null || c.subject().isBlank(), "template.subject_not_allowed",
                    "Assunto só existe no e-mail");
        }
        Guard.require(c.providerTemplateName() == null || c.providerTemplateName().matches("[a-z0-9_]{1,120}"),
                "template.provider_name_invalid", "Nome no provedor em minúsculas, números e _");
        this.name = c.name().trim();
        this.purpose = c.purpose();
        this.subject = channel == ChannelType.EMAIL ? c.subject().trim() : null;
        this.body = c.body();
        this.variables = c.variablesJson() == null ? "[]" : c.variablesJson();
        String lang = c.language() == null || c.language().isBlank() ? "pt_BR" : c.language().trim();
        Guard.require(lang.matches("[a-z]{2}(_[A-Z]{2})?"), "template.language_invalid", "Idioma inválido (ex.: pt_BR)");
        this.language = lang;
        this.providerTemplateName = c.providerTemplateName();
        if (channel == ChannelType.WHATSAPP) {
            this.providerCategory = c.providerCategory() == null ? ProviderCategory.UTILITY : c.providerCategory();
        } else {
            Guard.require(c.providerCategory() == null, "template.category_not_applicable",
                    "Categoria no provedor só se aplica a templates de WhatsApp");
            this.providerCategory = null;
        }
    }

    /** Registra a decisão do provedor sobre o template (WhatsApp). */
    public void registerApproval(ApprovalStatus decision, String providerTemplateName, String reference, String note,
                                 Instant decidedAt, UUID registeredBy) {
        Guard.requireState(channel == ChannelType.WHATSAPP, "template.approval_not_applicable",
                "Aprovação prévia só se aplica a templates de WhatsApp");
        Guard.requireState(status == TemplateStatus.DRAFT, "template.not_draft", "Aprovação registrada antes da publicação");
        Guard.require(decision == ApprovalStatus.APPROVED || decision == ApprovalStatus.REJECTED
                || decision == ApprovalStatus.PENDING, "template.approval_invalid", "Decisão inválida");
        String providerName = providerTemplateName == null || providerTemplateName.isBlank() ? this.providerTemplateName
                : providerTemplateName.trim().toLowerCase(Locale.ROOT);
        Guard.require(decision != ApprovalStatus.APPROVED || providerName != null, "template.provider_name_required",
                "Informe o nome do template aprovado no provedor");
        Guard.require(providerName == null || providerName.matches("[a-z0-9_]{1,120}"), "template.provider_name_invalid",
                "Nome no provedor em minúsculas, números e _");
        Guard.require(decision != ApprovalStatus.REJECTED || (note != null && !note.isBlank()),
                "template.rejection_note_required", "Informe o motivo da reprovação");
        Guard.require(note == null || note.length() <= 500, "template.approval_note_too_long", "Observação até 500 caracteres");
        Guard.require(reference == null || reference.length() <= 120, "template.approval_reference_too_long",
                "Referência até 120 caracteres");
        this.approvalStatus = decision;
        this.providerTemplateName = providerName;
        this.approvalReference = reference == null || reference.isBlank() ? null : reference.trim();
        this.approvalNote = note == null || note.isBlank() ? null : note.trim();
        this.approvalDecidedAt = decision == ApprovalStatus.PENDING ? null : decidedAt;
        this.approvalRegisteredBy = registeredBy;
    }

    public void publish(Instant now, UUID by) {
        Guard.requireState(status == TemplateStatus.DRAFT, "template.not_draft", "Somente rascunhos são publicados");
        if (channel == ChannelType.WHATSAPP) {
            Guard.requireState(approvalStatus == ApprovalStatus.APPROVED && providerTemplateName != null,
                    "template.not_approved", "Template de WhatsApp precisa estar aprovado no provedor para ser publicado");
        }
        this.status = TemplateStatus.PUBLISHED;
        this.publishedAt = now;
        this.publishedBy = by;
    }

    public void retire(Instant now) {
        Guard.requireState(status != TemplateStatus.RETIRED, "template.already_retired", "Template já retirado");
        this.status = TemplateStatus.RETIRED;
        this.retiredAt = now;
    }

    public boolean isUsable() {
        return status == TemplateStatus.PUBLISHED;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getCode() {
        return code;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public String getName() {
        return name;
    }

    public TemplatePurpose getPurpose() {
        return purpose;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getVariables() {
        return variables;
    }

    public String getLanguage() {
        return language;
    }

    public TemplateStatus getStatus() {
        return status;
    }

    public String getProviderTemplateName() {
        return providerTemplateName;
    }

    public ProviderCategory getProviderCategory() {
        return providerCategory;
    }

    /** Template de autenticação (código de acesso) no provedor: formato fixo da Meta com botão "copiar código". */
    public boolean isAuthentication() {
        return providerCategory == ProviderCategory.AUTHENTICATION;
    }

    public ApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }

    public String getApprovalReference() {
        return approvalReference;
    }

    public String getApprovalNote() {
        return approvalNote;
    }

    public Instant getApprovalDecidedAt() {
        return approvalDecidedAt;
    }

    public UUID getApprovalRegisteredBy() {
        return approvalRegisteredBy;
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
}
