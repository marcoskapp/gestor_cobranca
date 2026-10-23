package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Posição dos títulos do caso (cache do módulo debt, atualizado a cada sincronização): base dos
 * critérios de fila (atraso e valor) e da elegibilidade.
 *
 * @param openBalance     saldo em aberto dos títulos em cobrança (OPEN e SUSPENDED)
 * @param openTitles      títulos OPEN com saldo
 * @param suspendedTitles títulos SUSPENDED com saldo
 * @param disputedTitles  títulos OPEN bloqueados por contestação
 * @param oldestDueDate   menor vencimento entre os títulos em cobrança (nulo sem títulos)
 */
public record CaseBalance(Money openBalance, int openTitles, int suspendedTitles, int disputedTitles,
                          LocalDate oldestDueDate) {

    public static final CaseBalance EMPTY = new CaseBalance(Money.ZERO, 0, 0, 0, null);

    public CaseBalance {
        openBalance = openBalance == null ? Money.ZERO : openBalance;
        Guard.require(!openBalance.isNegative(), "case.balance_invalid", "Saldo do caso não pode ser negativo");
        Guard.require(openTitles >= 0 && suspendedTitles >= 0 && disputedTitles >= 0 && disputedTitles <= openTitles,
                "case.balance_invalid", "Contagem de títulos inválida");
    }

    /** Há títulos em cobrança (em aberto ou suspensos). */
    public boolean inCollection() {
        return openTitles + suspendedTitles > 0;
    }

    public long daysOverdue(LocalDate today) {
        if (oldestDueDate == null || !today.isAfter(oldestDueDate)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(oldestDueDate, today);
    }
}
