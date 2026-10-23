package br.com.gestorcobranca.legal.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Processos judiciais (etapas 5 a 7): cadastro com número CNJ validado (dígito verificador,
 * segmento, ano = ano da distribuição; único na assessoria), via judicial autorizada pelo credor,
 * polos (devedor principal e coobrigados vigentes dos contratos dos títulos), andamentos manuais
 * ou capturados e situação. O cadastro do primeiro processo leva o caso jurídico a FILED; a
 * citação registra a interrupção da prescrição no debt e leva FILED a IN_PROGRESS; o levantamento
 * de valores soma ao valor recuperado.
 */
public interface LawsuitUseCases {

    LawsuitView register(UUID matterId, LawsuitCommand command);

    LawsuitView get(UUID lawsuitId);

    List<LawsuitView> list(UUID matterId);

    LawsuitView update(UUID lawsuitId, UpdateLawsuitCommand command);

    /** @param status ACTIVE, SUSPENDED, ARCHIVED ou TERMINATED */
    LawsuitView changeStatus(UUID lawsuitId, String status, String reason);

    PageResponse<MovementView> movements(UUID lawsuitId, PageQuery page);

    /**
     * Lançamento manual de andamento, com efeitos (citação, levantamento, situação da garantia) e,
     * opcionalmente, o prazo decorrente.
     */
    MovementResult recordMovement(UUID lawsuitId, MovementCommand command);

    /**
     * Captura de andamentos pelo provedor de dados processuais (DataJud). Com o adaptador manual
     * responde {@code legal.provider_manual}.
     */
    SyncResult synchronize(UUID lawsuitId);

    /**
     * @param cnjNumber número com ou sem máscara
     * @param route     EXECUTION, MONITORY_ACTION, COLLECTION_ACTION ou SEARCH_AND_SEIZURE
     * @param creditorPole ACTIVE (padrão) ou PASSIVE
     * @param debtorIds polo contrário (vazio = devedor do caso jurídico)
     */
    record LawsuitCommand(String cnjNumber, String route, String actionClass, String creditorPole, Set<UUID> debtorIds,
                          BigDecimal claimAmount, LocalDate filedOn, String courtName, String courtUnit) {
    }

    record UpdateLawsuitCommand(String courtName, String courtUnit, String actionClass, BigDecimal claimAmount) {
    }

    /**
     * @param type             natureza (MovementType)
     * @param amount           avaliação, arrematação, adjudicação ou levantamento
     * @param collateralId     garantia afetada (com {@code collateralStatus}, atualiza a situação no debt)
     * @param collateralStatus UNDER_ENFORCEMENT, SEIZED, CONSOLIDATED, AUCTIONED, RELEASED…
     * @param deadline         prazo decorrente do andamento (opcional)
     */
    record MovementCommand(LocalDate occurredOn, String type, String description, BigDecimal amount, UUID collateralId,
                           String collateralStatus, LegalDeadlineUseCases.DeadlineCommand deadline) {
    }

    record LawsuitView(UUID id, UUID matterId, String cnjNumber, String cnjFormatted, String courtCode,
                       String courtName, String courtUnit, String route, String actionClass, String creditorPole,
                       Set<UUID> debtorIds, BigDecimal claimAmount, LocalDate filedOn, String status,
                       String statusReason, String provider, Instant lastSyncedAt, long version) {
    }

    record MovementView(UUID id, UUID lawsuitId, LocalDate occurredOn, String type, String description, String source,
                        String provider, String externalId, BigDecimal amount, UUID collateralId, Instant recordedAt,
                        UUID recordedBy) {
    }

    /**
     * @param prescriptionInterruptions interrupções registradas no debt (citação)
     */
    record MovementResult(MovementView movement, LegalDeadlineUseCases.DeadlineView deadline,
                          LegalMatterUseCases.MatterView matter, int prescriptionInterruptions) {
    }

    record SyncResult(UUID lawsuitId, String provider, boolean found, int captured, int duplicates, Instant syncedAt) {
    }
}
