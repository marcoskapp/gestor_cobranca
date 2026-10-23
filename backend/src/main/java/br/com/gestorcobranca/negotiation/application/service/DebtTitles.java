package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.ComponentBalanceView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleBalanceView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.domain.model.calculation.TitleSnapshot;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Leitura dos títulos do devedor pelo módulo debt (respeitando o escopo de credores do ator) e
 * conversão do razão por componente em {@link TitleSnapshot} para o cálculo.
 */
@Component
class DebtTitles {

    static final String OPEN = "OPEN";

    private final DebtTitleUseCases titles;

    DebtTitles(DebtTitleUseCases titles) {
        this.titles = titles;
    }

    /** Títulos do devedor no credor, indexados por id. */
    Map<UUID, TitleView> ofDebtor(UUID debtorId, UUID creditorId) {
        return titles.listByDebtor(debtorId).stream().filter(t -> creditorId.equals(t.creditorId()))
                .collect(Collectors.toMap(TitleView::id, Function.identity()));
    }

    /** Ids dos títulos em cobrança (OPEN) do devedor no credor. */
    List<UUID> openIds(Map<UUID, TitleView> debtorTitles) {
        return debtorTitles.values().stream().filter(t -> OPEN.equals(t.status()))
                .sorted(java.util.Comparator.comparing(TitleView::dueDate).thenComparing(TitleView::id))
                .map(TitleView::id).toList();
    }

    List<TitleSnapshot> snapshots(Collection<UUID> titleIds, Map<UUID, TitleView> debtorTitles) {
        return titleIds.stream().map(id -> snapshot(debtorTitles.get(id), titles.balance(id))).toList();
    }

    private static TitleSnapshot snapshot(TitleView title, TitleBalanceView balance) {
        Map<String, BigDecimal> byComponent = balance.components().stream()
                .collect(Collectors.toMap(ComponentBalanceView::component, ComponentBalanceView::balance, BigDecimal::add));
        return new TitleSnapshot(title.id(), title.portfolioId(), title.dueDate(), money(byComponent, "PRINCIPAL"),
                money(byComponent, "INTEREST"), money(byComponent, "FINE"), money(byComponent, "FEES"),
                money(byComponent, "HONORARIUM"), Money.ofNullable(balance.paidTotal()));
    }

    private static Money money(Map<String, BigDecimal> values, String component) {
        BigDecimal v = values.get(component);
        return v == null || v.signum() <= 0 ? Money.ZERO : Money.of(v);
    }
}
