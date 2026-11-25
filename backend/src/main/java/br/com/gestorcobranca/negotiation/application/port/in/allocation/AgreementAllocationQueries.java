package br.com.gestorcobranca.negotiation.application.port.in.allocation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Leituras do acordo para validar o destino e alocar um pagamento (payment). O escopo é o mesmo da consulta de acordos
 * (credor do ator; devedor do portal só os próprios). Ator interno precisa de {@code AGREEMENT_VIEW}; a pessoa do
 * credor (ator {@code CREDITOR}) e o token de integração com {@code PAYMENT_REGISTER_EXTERNAL} ({@code payments:write}
 * sem {@code agreements:read} — decisão 1518), que nunca consultam acordos, são dispensados — a autorização da ação é do caso de uso do payment, conferida antes. O resultado nunca vai para a tela.
 */
public interface AgreementAllocationQueries {

    AllocationAgreement agreement(UUID agreementId);

    /** Acordos ativos (FORMALIZED ou IN_PAYMENT) do credor e devedor. */
    List<AllocationAgreement> active(UUID creditorId, UUID debtorId);

    /** @param status FORMALIZED, IN_PAYMENT, SETTLED, BROKEN, CANCELLED, RENEGOTIATED */
    record AllocationAgreement(UUID id, UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId, String status,
                               List<UUID> titleIds, List<AllocationInstallment> installments) {
    }

    /** @param status OPEN, PARTIALLY_PAID, OVERDUE, PAID, CANCELLED */
    record AllocationInstallment(UUID id, int number, LocalDate dueDate, BigDecimal amount, BigDecimal paidAmount,
                                 String status) {
    }
}
