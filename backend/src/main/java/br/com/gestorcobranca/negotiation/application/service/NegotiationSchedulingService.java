package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.NegotiationScheduling;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;

/**
 * Rotina diária sob demanda, na assessoria do ator de sistema corrente: delega à mesma execução do agendamento
 * ({@link NegotiationJobs#runDaily}), que abre uma transação por lote.
 */
@Service
class NegotiationSchedulingService implements NegotiationScheduling {

    private final NegotiationJobs jobs;
    private final CurrentActor currentActor;

    NegotiationSchedulingService(NegotiationJobs jobs, CurrentActor currentActor) {
        this.jobs = jobs;
        this.currentActor = currentActor;
    }

    @Override
    public void runDailyMaintenance() {
        Actor actor = currentActor.get();
        if (!actor.isSystem()) {
            throw new IllegalStateException("rotina diária do negotiation: somente o ator de sistema da assessoria");
        }
        jobs.runDaily(actor.tenantId());
    }
}
