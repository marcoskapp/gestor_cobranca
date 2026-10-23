package br.com.gestorcobranca.debt.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Contrato de origem e a natureza do seu instrumento (contrato, CCB, nota promissória...), que
 * define a via de cobrança judicial e a regra de prescrição. A reclassificação é auditada e
 * recalcula a prescrição dos títulos do contrato.
 */
public interface OriginContractUseCases {

    ContractView get(UUID contractId);

    ContractView classify(UUID contractId, ClassifyContractCommand command);

    /**
     * @param instrumentType CONTRACT, CCB, PROMISSORY_NOTE, DUPLICATE, CHECK, DEBT_CONFESSION ou OTHER
     * @param executiveTitle se o instrumento é título executivo extrajudicial
     */
    record ClassifyContractCommand(String instrumentType, boolean executiveTitle) {
    }

    record ContractView(UUID id, UUID creditorId, UUID portfolioId, UUID debtorId, String externalId, String product,
                        LocalDate signedAt, BigDecimal originalAmount, String instrumentType, boolean executiveTitle) {
    }
}
