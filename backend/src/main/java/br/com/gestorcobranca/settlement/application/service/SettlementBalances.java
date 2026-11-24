package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.BalanceView;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.StatementBalance;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.repository.SettlementStatementRepository;
import br.com.gestorcobranca.shared.domain.Money;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Saldo a repassar (ou a receber) dos demonstrativos aprovados, por credor. Só cálculo: quem chama já conferiu
 * permissão e escopo (repasses da assessoria, posição interna e o portal do credor).
 */
@Component
class SettlementBalances {

    private final SettlementStatementRepository statements;

    SettlementBalances(SettlementStatementRepository statements) {
        this.statements = statements;
    }

    /** @param creditors credores considerados ({@code null} = todos do tenant) */
    List<BalanceView> of(Set<UUID> creditors) {
        Map<UUID, List<StatementBalance>> byCreditor = new LinkedHashMap<>();
        Map<UUID, Money[]> totals = new LinkedHashMap<>();
        for (SettlementStatement s : statements.findClosed(creditors)) {
            Money outstanding = s.outstanding();
            if (!outstanding.isPositive()) {
                continue;
            }
            byCreditor.computeIfAbsent(s.getCreditorId(), k -> new ArrayList<>()).add(new StatementBalance(s.getId(),
                    s.getSequenceNumber(), s.getPeriodStart(), s.getPeriodEnd(), s.getStatus().name(),
                    s.getNetTransferAmount().amount(), s.getTransferredAmount().amount(), outstanding.amount()));
            Money[] t = totals.computeIfAbsent(s.getCreditorId(), k -> new Money[]{Money.ZERO, Money.ZERO});
            if (s.getNetTransferAmount().isPositive()) {
                t[0] = t[0].plus(outstanding);
            } else {
                t[1] = t[1].plus(outstanding);
            }
        }
        return byCreditor.entrySet().stream().map(e -> new BalanceView(e.getKey(), totals.get(e.getKey())[0].amount(),
                totals.get(e.getKey())[1].amount(), List.copyOf(e.getValue()))).toList();
    }
}
