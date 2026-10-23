package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionPeriod;
import br.com.gestorcobranca.incentive.domain.repository.LedgerRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Períodos de competência: um lançamento é apurado no mês do pagamento ou, se esse mês já foi
 * fechado, no primeiro período aberto seguinte.
 */
@Component
class PeriodCalendar {

    private final LedgerRepository ledger;
    private final FactStore facts;

    PeriodCalendar(LedgerRepository ledger, FactStore facts) {
        this.ledger = ledger;
        this.facts = facts;
    }

    boolean isOpen(LocalDate periodStart) {
        return periodStart != null && ledger.findPeriod(periodStart).map(p -> !p.isClosed()).orElse(true);
    }

    /** Primeiro período aberto a partir do mês da data (cria o período se necessário). */
    LocalDate firstOpenFrom(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        while (true) {
            CommissionPeriod period = ensure(month);
            if (!period.isClosed()) {
                return period.getPeriodStart();
            }
            month = month.plusMonths(1);
        }
    }

    CommissionPeriod ensure(YearMonth month) {
        return ledger.findPeriod(month.atDay(1)).orElseGet(() -> {
            facts.ensurePeriod(month.atDay(1), month.atEndOfMonth());
            return ledger.findPeriod(month.atDay(1)).orElseThrow();
        });
    }
}
