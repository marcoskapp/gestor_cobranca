package br.com.gestorcobranca.incentive.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Monitoria de qualidade e ocorrências de conduta (insumos do portão de qualidade e do zerador).
 * Registro exige {@code QUALITY_EVALUATE}; consulta segue o escopo próprio/equipe/tudo.
 */
public interface QualityUseCases {

    FormView createForm(FormCommand command);

    FormView deactivateForm(UUID formId);

    List<FormView> forms();

    EvaluationView evaluate(EvaluationCommand command);

    /** @param userId null = todos os visíveis */
    PageResponse<EvaluationView> evaluations(UUID userId, LocalDate from, LocalDate to, PageQuery page);

    OccurrenceView registerOccurrence(OccurrenceCommand command);

    /** Dispensa (contestação procedente); exige {@code COMMISSION_STATEMENT_MANAGE}. */
    OccurrenceView dismissOccurrence(UUID occurrenceId, String reason);

    PageResponse<OccurrenceView> occurrences(UUID userId, LocalDate from, LocalDate to, PageQuery page);

    record CriterionInput(String code, String description, BigDecimal weight, boolean critical) {
    }

    record FormCommand(String name, String description, List<CriterionInput> criteria) {
    }

    record FormView(UUID id, String name, String description, boolean active, List<CriterionInput> criteria) {
    }

    /** @param referenceType CALL, CONVERSATION, CASE, AGREEMENT ou OTHER */
    record EvaluationCommand(UUID formId, UUID userId, String referenceType, UUID referenceId, LocalDate evaluatedOn,
                             Map<String, BigDecimal> scores, String comments) {
    }

    record EvaluationView(UUID id, UUID formId, UUID userId, UUID evaluatorId, String referenceType, UUID referenceId,
                          LocalDate evaluatedOn, BigDecimal score, boolean criticalFailure,
                          Map<String, BigDecimal> scores, String comments, Instant createdAt) {
    }

    /** @param type SEVERE_COMPLIANCE_VIOLATION, COMPLIANCE_VIOLATION, COMPLAINT ou UPHELD_DISPUTE */
    record OccurrenceCommand(UUID userId, String type, LocalDate occurredOn, String description, String referenceType,
                             UUID referenceId) {
    }

    record OccurrenceView(UUID id, UUID userId, String type, LocalDate occurredOn, String description,
                          String referenceType, UUID referenceId, String status, UUID registeredBy, UUID dismissedBy,
                          Instant dismissedAt, String dismissalReason) {
    }
}
