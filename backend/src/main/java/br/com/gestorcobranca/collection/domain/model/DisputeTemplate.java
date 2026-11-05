package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Versão de um modelo de e-mail das contestações (imutável): pedido ao credor por tipo de contestação ou resposta
 * ao devedor por resultado. A versão de maior número é a vigente; sem versão gravada vale o modelo padrão do
 * sistema (versão 0). Variáveis entre chaves duplas, resolvidas por {@code DisputeTemplateRenderer}.
 */
@Entity
@Immutable
@Table(name = "dispute_template", schema = "collection")
public class DisputeTemplate extends TenantScopedEntity {

    public static final int SUBJECT_MAX = 200;
    public static final int BODY_MAX = 8000;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private DisputeTemplateKind kind;

    @Column(name = "template_key", nullable = false, length = 30, updatable = false)
    private String templateKey;

    @Column(name = "template_version", nullable = false, updatable = false)
    private int templateVersion;

    @Column(name = "subject", nullable = false, length = SUBJECT_MAX, updatable = false)
    private String subject;

    @Column(name = "body", nullable = false, length = BODY_MAX, updatable = false)
    private String body;

    protected DisputeTemplate() {
    }

    public DisputeTemplate(DisputeTemplateKind kind, String templateKey, int templateVersion, String subject,
                           String body) {
        Guard.require(kind != null && templateKey != null, "dispute_template.key_required", "Modelo obrigatório");
        Guard.require(templateVersion >= 1, "dispute_template.version_invalid", "Versão inválida");
        Guard.require(subject != null && !subject.isBlank() && subject.trim().length() <= SUBJECT_MAX,
                "dispute_template.subject_required", "Assunto obrigatório (até %d caracteres)".formatted(SUBJECT_MAX));
        Guard.require(body != null && !body.isBlank() && body.trim().length() <= BODY_MAX,
                "dispute_template.body_required", "Texto obrigatório (até %d caracteres)".formatted(BODY_MAX));
        this.kind = kind;
        this.templateKey = templateKey;
        this.templateVersion = templateVersion;
        this.subject = subject.trim();
        this.body = body.trim();
    }

    public DisputeTemplateKind getKind() {
        return kind;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }
}
