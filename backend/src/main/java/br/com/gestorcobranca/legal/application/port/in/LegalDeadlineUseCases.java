package br.com.gestorcobranca.legal.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Prazos jurídicos com contagem em dias úteis (CPC arts. 219, 220 e 224): feriados do calendário
 * do compliance, suspensões forenses cadastradas (por tribunal ou gerais) e recesso de fim de ano
 * (parâmetro da assessoria). Alertas escalonados pelos níveis da assessoria (varredura diária),
 * vencidos sem cumprimento passam a perdidos. Suspensões novas recalculam os prazos pendentes.
 */
public interface LegalDeadlineUseCases {

    DeadlineView create(UUID matterId, DeadlineCommand command);

    /** Calcula o vencimento sem gravar. */
    DueDatePreview preview(DueDateQuery query);

    DeadlineView complete(UUID deadlineId, LocalDate completedOn, String note);

    DeadlineView cancel(UUID deadlineId, String reason);

    List<DeadlineView> listByMatter(UUID matterId);

    /** Agenda de prazos no escopo do ator ({@code responsibleUserId} = "me" na API). */
    PageResponse<DeadlineView> agenda(AgendaFilter filter, PageQuery page);

    List<SuspensionView> suspensions();

    /** Exige {@code LEGAL_MANAGE}, todos os credores e usuário interno. */
    SuspensionView addSuspension(SuspensionCommand command);

    void removeSuspension(UUID suspensionId, String reason);

    /**
     * @param kind          RESPONSE, APPEAL, PAYMENT, FILING, HEARING_PREPARATION, EXTRAJUDICIAL,
     *                      RESTRICTION_REMOVAL ou OTHER
     * @param countingMode  BUSINESS_DAYS (padrão), CALENDAR_DAYS ou FIXED_DATE
     * @param startEvent    INTIMATION (padrão) ou DJE_AVAILABILITY
     * @param lawsuitId     processo (define o tribunal das suspensões aplicáveis)
     * @param responsibleUserId responsável (nulo = responsável do caso jurídico)
     */
    record DeadlineCommand(String kind, String description, UUID lawsuitId, UUID measureId, LocalDate startDate,
                           String startEvent, Integer termDays, String countingMode, LocalDate fixedDate,
                           UUID responsibleUserId) {
    }

    /** @param courtCode tribunal J.TR (opcional) */
    record DueDateQuery(LocalDate startDate, String startEvent, Integer termDays, String countingMode,
                        LocalDate fixedDate, String courtCode) {
    }

    /** @param nonBusinessDays dias não úteis entre o início e o vencimento (fins de semana, feriados, suspensões) */
    record DueDatePreview(LocalDate dueDate, List<LocalDate> nonBusinessDays) {
    }

    /** @param statuses PENDING, MET, MISSED, CANCELLED */
    record AgendaFilter(UUID matterId, Set<String> statuses, LocalDate dueFrom, LocalDate dueTo,
                        UUID responsibleUserId) {
    }

    /**
     * @param businessDaysRemaining dias úteis até o vencimento (negativo = vencido); nulo se encerrado
     */
    record DeadlineView(UUID id, UUID matterId, UUID lawsuitId, UUID movementId, UUID measureId, String kind,
                        String description, String courtCode, LocalDate startDate, String startEvent, Integer termDays,
                        String countingMode, LocalDate fixedDate, LocalDate dueDate, UUID responsibleUserId,
                        String status, LocalDate completedOn, String statusReason, Integer lastAlertLevel,
                        Integer businessDaysRemaining, Instant createdAt, long version) {
    }

    record SuspensionCommand(LocalDate startDate, LocalDate endDate, String courtCode, String reason) {
    }

    record SuspensionView(UUID id, LocalDate startDate, LocalDate endDate, String courtCode, String reason,
                          Instant createdAt) {
    }
}
