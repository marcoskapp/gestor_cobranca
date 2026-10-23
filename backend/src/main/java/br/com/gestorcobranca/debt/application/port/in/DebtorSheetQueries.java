package br.com.gestorcobranca.debt.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ficha consolidada do devedor (F05): identificação mascarada, contatos utilizáveis e todos os
 * títulos visíveis ao ator agrupados por credor, contratos em que é coobrigado e garantias
 * associadas. Usada pela tela de atendimento e pelo módulo collection.
 */
public interface DebtorSheetQueries {

    DebtorSummary summary(UUID debtorId);

    /**
     * @param totalOpenBalance saldo em aberto dos títulos em cobrança (OPEN/SUSPENDED)
     * @param maxDaysOverdue   maior atraso entre os títulos em cobrança
     * @param coObligations    contratos (de credores do escopo) em que o devedor é coobrigado vigente
     * @param collateral       garantias dos contratos visíveis em que o devedor é principal ou coobrigado
     */
    record DebtorSummary(UUID debtorId, String name, String maskedDocument, String personType, String status,
                         List<ContactSummary> contacts, List<CreditorPosition> creditors, BigDecimal totalOpenBalance,
                         long maxDaysOverdue, int titleCount, List<CoObligationSummary> coObligations,
                         List<CollateralSummary> collateral) {
    }

    /**
     * @param role        GUARANTOR_AVAL, GUARANTOR_SURETY, CO_DEBTOR ou THIRD_PARTY_COLLATERAL_PROVIDER
     * @param openBalance saldo em aberto dos títulos do contrato em cobrança
     */
    record CoObligationSummary(UUID contractId, UUID creditorId, String contractExternalId, String role,
                               LocalDate sinceDate, UUID principalDebtorId, BigDecimal openBalance,
                               long maxDaysOverdue, int titleCount) {
    }

    /**
     * @param debtorRole     papel do devedor no contrato da garantia (PRINCIPAL ou o papel de coobrigado)
     * @param estimatedValue valor recuperável estimado (última avaliação)
     */
    record CollateralSummary(UUID collateralId, UUID contractId, UUID creditorId, String debtorRole, String type,
                             String assetSummary, String status, BigDecimal estimatedValue, LocalDate lastAppraisalDate) {
    }

    record ContactSummary(UUID contactId, String type, String maskedValue, String label, boolean whatsapp,
                          String status, int priority) {
    }

    record CreditorPosition(UUID creditorId, BigDecimal openBalance, long maxDaysOverdue, List<TitleSummary> titles) {
    }

    record TitleSummary(UUID titleId, UUID portfolioId, String externalId, Integer installmentNumber, LocalDate dueDate,
                        BigDecimal principalAmount, BigDecimal openBalance, String status, boolean disputeBlocked,
                        long daysOverdue) {
    }
}
