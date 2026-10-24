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

import java.util.UUID;

/**
 * Chamada de ferramenta pedida pelo modelo num turno (append-only): argumentos e resultado
 * resumidos — somente referências, ids, códigos e valores do motor, nunca texto do devedor nem dados
 * pessoais —, resultado da validação do servidor e referência ao agregado afetado.
 */
@Entity
@Table(name = "turn_tool_call", schema = "assistant")
public class ToolCallRecord extends TenantScopedEntity {

    @Column(name = "turn_id", nullable = false, updatable = false)
    private UUID turnId;

    @Column(name = "sequence", nullable = false, updatable = false)
    private int sequence;

    @Column(name = "tool_name", nullable = false, length = 40, updatable = false)
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "arguments", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String arguments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String result;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20, updatable = false)
    private ToolCallOutcome outcome;

    @Column(name = "error_code", length = 80, updatable = false)
    private String errorCode;

    @Column(name = "reference_type", length = 40, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    protected ToolCallRecord() {
    }

    public ToolCallRecord(UUID turnId, int sequence, String toolName, String argumentsJson, String resultJson,
                          ToolCallOutcome outcome, String errorCode, String referenceType, UUID referenceId,
                          long durationMs) {
        Guard.require(turnId != null && toolName != null && outcome != null, "assistant_tool_call.invalid",
                "Chamada de ferramenta incompleta");
        this.turnId = turnId;
        this.sequence = sequence;
        this.toolName = toolName.length() > 40 ? toolName.substring(0, 40) : toolName;
        this.arguments = argumentsJson == null ? "{}" : argumentsJson;
        this.result = resultJson == null ? "{}" : resultJson;
        this.outcome = outcome;
        this.errorCode = errorCode == null || errorCode.length() <= 80 ? errorCode : errorCode.substring(0, 80);
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.durationMs = Math.max(0, durationMs);
    }

    public UUID getTurnId() {
        return turnId;
    }

    public int getSequence() {
        return sequence;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public String getResult() {
        return result;
    }

    public ToolCallOutcome getOutcome() {
        return outcome;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
