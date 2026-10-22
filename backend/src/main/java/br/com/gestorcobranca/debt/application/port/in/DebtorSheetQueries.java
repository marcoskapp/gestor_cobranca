package br.com.gestorcobranca.debt.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ficha consolidada do devedor (F05): identificação mascarada, contatos utilizáveis e todos os
 * títulos visíveis ao ator agrupados por credor. Usada pela tela de atendimento e pelo módulo
 * collection.
 */
public interface DebtorSheetQueries {

    DebtorSummary summary(UUID debtorId);

    /**
     * @param totalOpenBalance saldo em aberto dos títulos em cobrança (OPEN/SUSPENDED)
     * @param maxDaysOverdue   maior atraso entre os títulos em cobrança
     */
    record DebtorSummary(UUID debtorId, String name, String maskedDocument, String personType, String status,
                         List<ContactSummary> contacts, List<CreditorPosition> creditors, BigDecimal totalOpenBalance,
                         long maxDaysOverdue, int titleCount) {
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
