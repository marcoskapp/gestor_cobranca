package br.com.gestorcobranca.shared.events.legal;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Caso jurídico ({@code LegalMatter}) mudou de fase (docs/design/juridico.md §B.2): ELIGIBLE,
 * UNDER_REVIEW, AWAITING_AUTHORIZATION, EXTRAJUDICIAL, FILED, IN_PROGRESS, ENFORCEMENT,
 * SETTLED_IN_COURT, CLOSED, NOT_VIABLE ou RETURNED_TO_CREDITOR. Insumo para réguas (pausa de
 * campanhas incompatíveis), assistente (caso jurídico fora do escopo), prestação de contas e
 * painéis.
 *
 * @param previousStage   nulo na indicação (o caso jurídico acaba de nascer em ELIGIBLE)
 * @param route           via principal escolhida (nome do enum {@code LegalRoute}); nula antes da viabilidade
 * @param responsibleType NONE, INTERNAL ou LAW_FIRM
 * @param lawFirmId       escritório parceiro responsável, se houver
 * @param closureReason   motivo do encerramento quando {@code newStage = CLOSED}
 * @param debtAmount      valor da dívida considerado no caso jurídico
 */
public record LegalMatterStageChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID matterId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        List<UUID> titleIds,
        String previousStage,
        String newStage,
        String route,
        String responsibleType,
        UUID lawFirmId,
        String closureReason,
        BigDecimal debtAmount) implements IntegrationEvent {

    public LegalMatterStageChanged {
        titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
    }
}
