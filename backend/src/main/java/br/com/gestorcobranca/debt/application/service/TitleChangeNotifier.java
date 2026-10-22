package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.TitleStatus;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Comunica mudanças de título aos demais módulos e à inteligência de negócio, na transação do
 * caso de uso:
 * <ul>
 *   <li>{@link TitleStatusChanged} quando muda o status ou o saldo em aberto (ou quando o
 *       chamador indica outra mudança relevante para elegibilidade, como contestação ou
 *       carteira);</li>
 *   <li>{@code title.status_changed} no log de negócio quando muda o status.</li>
 * </ul>
 */
@Component
class TitleChangeNotifier {

    /** O que provocou a mudança (atributo {@code trigger} do evento de negócio). */
    enum Trigger { PAYMENT, REVERSAL, CHARGE, DISCOUNT, IMPORT, MANUAL, RETURN, TRANSFER, DISPUTE }

    private final ApplicationEventPublisher events;
    private final BusinessEventLogger businessEvents;
    private final CurrentActor currentActor;
    private final Clock clock;

    TitleChangeNotifier(ApplicationEventPublisher events, BusinessEventLogger businessEvents, CurrentActor currentActor,
                        Clock clock) {
        this.events = events;
        this.businessEvents = businessEvents;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    /** Publica somente se status ou saldo mudaram. */
    void changed(DebtTitle title, TitleStatus previousStatus, Money previousBalance, Trigger trigger) {
        notify(title, previousStatus, previousBalance, trigger, false);
    }

    /** Publica mesmo sem mudança de status/saldo (contestação, carteira). */
    void changedAlways(DebtTitle title, TitleStatus previousStatus, Money previousBalance, Trigger trigger) {
        notify(title, previousStatus, previousBalance, trigger, true);
    }

    private void notify(DebtTitle title, TitleStatus previousStatus, Money previousBalance, Trigger trigger,
                        boolean force) {
        boolean statusChanged = previousStatus != title.getStatus();
        boolean balanceChanged = !previousBalance.equals(title.getOpenBalance());
        if (!force && !statusChanged && !balanceChanged) {
            return;
        }
        events.publishEvent(new TitleStatusChanged(Ids.newId(), currentActor.tenantId(), Instant.now(clock),
                title.getId(), title.getDebtorId(), title.getCreditorId(), title.getPortfolioId(),
                previousStatus.name(), title.getStatus().name(), title.getOpenBalance().amount()));
        if (statusChanged) {
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.TITLE_STATUS_CHANGED)
                    .creditor(title.getCreditorId()).portfolio(title.getPortfolioId()).debtor(title.getDebtorId())
                    .reference("title", title.getId())
                    .amount(title.getOpenBalance())
                    .attr("from", previousStatus).attr("to", title.getStatus()).attr("trigger", trigger)
                    .attr("previousBalance", previousBalance.amount()));
        }
    }
}
