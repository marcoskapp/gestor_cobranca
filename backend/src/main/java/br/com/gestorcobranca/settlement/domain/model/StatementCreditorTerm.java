package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Termo do aceite ou da contestação feito pela pessoa do credor no portal do credor (ADR 0011): quem, quando, de onde,
 * a nova verificação do MFA (no aceite), a observação ou o motivo e o SHA-256 de cada arquivo do demonstrativo. Só de inclusão (gatilho no banco).
 */
@Entity
@Table(name = "statement_creditor_term", schema = "settlement")
public class StatementCreditorTerm extends TenantScopedEntity {

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;

    @Column(name = "decision", nullable = false, updatable = false, length = 20)
    private String decision;

    @Column(name = "creditor_user_id", nullable = false, updatable = false)
    private UUID creditorUserId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "client_ip", updatable = false, length = 64)
    private String clientIp;

    @Column(name = "mfa_verified_at", updatable = false)
    private Instant mfaVerifiedAt;

    @Column(name = "note", updatable = false, length = 2000)
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "files", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String files;

    @Column(name = "term_sha256", nullable = false, updatable = false, length = 64)
    private String termSha256;

    protected StatementCreditorTerm() {
    }

    public StatementCreditorTerm(UUID statementId, String decision, UUID creditorUserId, UUID sessionId, Instant recordedAt,
                                 String clientIp, Instant mfaVerifiedAt, String note, String filesJson,
                                 String termSha256) {
        this.statementId = statementId;
        this.decision = decision;
        this.creditorUserId = creditorUserId;
        this.sessionId = sessionId;
        this.recordedAt = recordedAt;
        this.clientIp = clientIp == null || clientIp.length() <= 64 ? clientIp : clientIp.substring(0, 64);
        this.mfaVerifiedAt = mfaVerifiedAt;
        this.note = note;
        this.files = filesJson;
        this.termSha256 = termSha256;
    }

    public UUID getStatementId() {
        return statementId;
    }

    public String getDecision() {
        return decision;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getTermSha256() {
        return termSha256;
    }
}
