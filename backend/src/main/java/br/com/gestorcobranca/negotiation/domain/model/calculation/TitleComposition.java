package br.com.gestorcobranca.negotiation.domain.model.calculation;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Composição de um título numa data de referência.
 *
 * @param gross            valores por componente (encargos calculados + reconhecidos)
 * @param recognized       parcela de {@code gross} que já estava reconhecida no razão
 * @param honorariumBase   base de cálculo dos honorários calculados (sem os reconhecidos)
 * @param correctionFactor fator do índice aplicado (1 sem correção)
 * @param lines            memória de cálculo do título
 */
public record TitleComposition(UUID titleId, UUID portfolioId, LocalDate dueDate, long daysOverdue, Breakdown gross,
                               Breakdown recognized, Money honorariumBase, Money paidTotal,
                               BigDecimal correctionFactor, List<CalculationLine> lines) {

    public TitleComposition {
        lines = List.copyOf(lines);
    }

    public Money total() {
        return gross.total();
    }
}
