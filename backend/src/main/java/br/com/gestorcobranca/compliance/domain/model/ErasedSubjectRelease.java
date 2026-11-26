package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Liberação do retorno do titular eliminado a pedido (decisão 1604): a partir dela, o documento volta a ser aceito na
 * carga e no cadastro. Imutável e uma por pedido. Segregação de funções: quem executou a eliminação não libera.
 */
@Entity
@Table(name = "erased_subject_release", schema = "compliance")
public class ErasedSubjectRelease extends TenantScopedEntity {

    public static final int REASON_MIN = 10;
    public static final int REASON_MAX = 500;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "reason", nullable = false, length = REASON_MAX, updatable = false)
    private String reason;

    @Column(name = "released_by", nullable = false, updatable = false)
    private UUID releasedBy;

    @Column(name = "released_at", nullable = false, updatable = false)
    private Instant releasedAt;

    protected ErasedSubjectRelease() {
    }

    /**
     * @param request a solicitação de eliminação executada
     * @param actorId quem libera (diferente de quem executou a eliminação)
     */
    public ErasedSubjectRelease(DataSubjectRequest request, String reason, UUID actorId, Instant now) {
        Guard.requireState(request.getType() == RequestType.ANONYMIZATION && request.getErasedSubjectHash() != null,
                "erased_subject_release.not_applicable", "Solicitação sem titular eliminado para liberar");
        Guard.require(actorId != null && now != null, "erased_subject_release.invalid", "Liberação incompleta");
        // sem o autor da eliminação (pedido anterior à V0515 sem auditoria) não há como garantir a segregação
        Guard.requireState(request.getExecutedBy() != null, "erased_subject_release.executor_unknown",
                "Não se sabe quem executou esta eliminação; a liberação do retorno não pode ser feita por aqui");
        Guard.requireState(!Objects.equals(actorId, request.getExecutedBy()), "erased_subject_release.same_person",
                "Quem executou a eliminação não pode liberar o retorno do titular");
        String trimmed = reason == null ? "" : reason.strip();
        Guard.require(trimmed.length() >= REASON_MIN && trimmed.length() <= REASON_MAX,
                "erased_subject_release.reason_invalid",
                "Informe o motivo da liberação (de %d a %d caracteres)".formatted(REASON_MIN, REASON_MAX));
        this.requestId = request.getId();
        this.reason = trimmed;
        this.releasedBy = actorId;
        this.releasedAt = now;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getReason() {
        return reason;
    }

    public UUID getReleasedBy() {
        return releasedBy;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
