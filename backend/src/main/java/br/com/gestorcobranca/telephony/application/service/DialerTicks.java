package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Pede um ciclo do discador para a campanha (job {@code telephony.dialer_tick}, um pendente por
 * campanha). Disparado ao ativar a campanha, quando um operador fica disponível, quando uma
 * chamada da campanha termina e, periodicamente, pelo agendamento do módulo.
 */
@Component
class DialerTicks {

    private final JobQueue jobQueue;

    DialerTicks(JobQueue jobQueue) {
        this.jobQueue = jobQueue;
    }

    void requestNow(UUID campaignId) {
        jobQueue.enqueue(DialerTickJob.TYPE, new DialerTickJob.Payload(campaignId), null, key(campaignId));
    }

    static String key(UUID campaignId) {
        return "dialer:" + campaignId;
    }
}
