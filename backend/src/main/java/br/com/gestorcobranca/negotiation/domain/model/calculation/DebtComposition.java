package br.com.gestorcobranca.negotiation.domain.model.calculation;

import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Composição da dívida de uma negociação (§2): por título e total. O total é a soma dos valores
 * já arredondados de cada título, para que cada título concilie individualmente com o razão.
 *
 * @param maxDaysOverdue maior atraso entre os títulos (seleciona a faixa de atraso da política)
 * @param notes          avisos do cálculo (ex.: competência do índice ainda não publicada)
 */
public record DebtComposition(LocalDate referenceDate, List<TitleComposition> titles, Breakdown total, Money paidTotal,
                              long maxDaysOverdue, List<String> notes) {

    public DebtComposition {
        titles = List.copyOf(titles);
        notes = List.copyOf(notes);
    }

    public List<UUID> titleIds() {
        return titles.stream().map(TitleComposition::titleId).toList();
    }
}
