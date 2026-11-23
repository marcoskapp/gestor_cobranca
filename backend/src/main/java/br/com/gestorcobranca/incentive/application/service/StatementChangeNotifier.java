package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.incentive.CommissionStatementsChanged;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Avisa os outros módulos que o valor a pagar dos extratos de um período mudou ({@link CommissionStatementsChanged}),
 * na transação de quem mudou: sem confirmação, sem aviso.
 */
@Component
class StatementChangeNotifier {

    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final Clock clock;

    StatementChangeNotifier(ApplicationEventPublisher events, CurrentActor currentActor, Clock clock) {
        this.events = events;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    void payableChanged(LocalDate periodStart) {
        events.publishEvent(new CommissionStatementsChanged(Ids.newId(), currentActor.get().tenantId(),
                Instant.now(clock), periodStart));
    }
}
