package br.com.gestorcobranca.assistant.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Turno amostrado para revisão humana (aleatório pela taxa da política, ou sempre quando houve
 * guardrail ou falha): nota de 1 a 5, veredito e etiquetas; o comentário livre é cifrado e descartado
 * na anonimização do devedor. Uma decisão por turno, sem revisão da própria configuração: quem revisa
 * não pode ser o autor da política aplicada no turno.
 */
@Entity
@Table(name = "turn_review", schema = "assistant")
public class TurnReview extends TenantScopedEntity {

    public static final List<String> TAGS = List.of("TONE", "ACCURACY", "TRANSPARENCY", "UNNECESSARY_HANDOFF",
            "MISSED_HANDOFF", "PRIVACY", "LEGAL_RISK", "OFFER_QUALITY");

    @Column(name = "turn_id", nullable = false, updatable = false)
    private UUID turnId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "policy_id", updatable = false)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sample_reason", nullable = false, length = 20, updatable = false)
    private SampleReason sampleReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReviewStatus status;

    @Column(name = "score")
    private Integer score;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", length = 30)
    private ReviewVerdict verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private String tags;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "comment_encrypted", length = 8000)
    private String comment;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected TurnReview() {
    }

    public TurnReview(AssistantTurn turn, SampleReason reason) {
        this.turnId = turn.getId();
        this.conversationId = turn.getConversationId();
        this.creditorId = turn.getCreditorId();
        this.portfolioId = turn.getPortfolioId();
        this.debtorId = turn.getDebtorId();
        this.policyId = turn.getPolicyId();
        this.sampleReason = reason;
        this.status = ReviewStatus.PENDING;
        this.tags = "[]";
    }

    public void review(UUID reviewer, int score, ReviewVerdict verdict, String tagsJson, String comment, Instant now) {
        Guard.requireState(status == ReviewStatus.PENDING, "assistant_review.already_reviewed", "Turno já avaliado");
        Guard.require(score >= 1 && score <= 5, "assistant_review.score_invalid", "Nota de 1 a 5");
        Guard.require(verdict != null, "assistant_review.verdict_required", "Veredito obrigatório");
        Guard.require(comment == null || comment.length() <= 2000, "assistant_review.comment_too_long",
                "Comentário até 2000 caracteres");
        Guard.require(verdict != ReviewVerdict.OUT_OF_POLICY || (comment != null && !comment.isBlank()),
                "assistant_review.comment_required", "Descreva o que ficou fora da política");
        this.status = ReviewStatus.REVIEWED;
        this.score = score;
        this.verdict = verdict;
        this.tags = tagsJson == null ? "[]" : tagsJson;
        this.comment = comment == null || comment.isBlank() ? null : comment.trim();
        this.reviewerId = reviewer;
        this.reviewedAt = now;
    }

    /** LGPD: o comentário livre pode citar o devedor. */
    public void anonymize() {
        this.comment = null;
    }

    public UUID getTurnId() {
        return turnId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public SampleReason getSampleReason() {
        return sampleReason;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public Integer getScore() {
        return score;
    }

    public ReviewVerdict getVerdict() {
        return verdict;
    }

    public String getTags() {
        return tags;
    }

    public String getComment() {
        return comment;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
