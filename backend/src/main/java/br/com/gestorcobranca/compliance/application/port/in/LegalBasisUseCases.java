package br.com.gestorcobranca.compliance.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bases legais do tratamento por credor e, opcionalmente, por carteira (RB09/LGPD): base do
 * art. 7º, papel da assessoria (operador por padrão, controlador ou co-controlador), referência
 * contratual, responsável jurídico, vigência sem sobreposição e prazo de retenção. Consultável
 * por outros módulos. Todas as operações exigem o credor no escopo.
 */
public interface LegalBasisUseCases {

    LegalBasisView register(RegisterLegalBasisCommand command);

    /** Encerra a vigência no último dia informado, preservando o registro. */
    LegalBasisView end(UUID recordId, LocalDate lastDay, String reason);

    List<LegalBasisView> listByCreditor(UUID creditorId);

    /** Registro vigente na data (nula = hoje): o da carteira, se houver, senão o geral do credor. */
    Optional<LegalBasisView> findEffective(UUID creditorId, UUID portfolioId, LocalDate date);

    /**
     * @param legalBasis     EXECUCAO_CONTRATO, EXERCICIO_REGULAR_DIREITOS, LEGITIMO_INTERESSE,
     *                       PROTECAO_CREDITO, CONSENTIMENTO ou OBRIGACAO_LEGAL
     * @param processingRole OPERADOR (padrão), CONTROLADOR ou CO_CONTROLADOR
     */
    record RegisterLegalBasisCommand(UUID creditorId, UUID portfolioId, String legalBasis, String processingRole,
                                     String contractReference, LocalDate validFrom, LocalDate validUntil,
                                     String legalResponsible, int retentionMonths, String notes) {
    }

    record LegalBasisView(UUID id, UUID creditorId, UUID portfolioId, String legalBasis, String processingRole,
                          String contractReference, LocalDate validFrom, LocalDate validUntil, String legalResponsible,
                          int retentionMonths, String notes, String endReason, Instant createdAt) {
    }
}
