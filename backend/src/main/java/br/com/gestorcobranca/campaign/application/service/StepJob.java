package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.CampaignProperties;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Job {@code campaign.step}: executa a etapa devida de uma inscrição (ou a ação de transição por
 * resposta pendente). Idempotente: o payload identifica etapa e iteração esperadas; inscrição em
 * outro estado ou reagendada → nada a fazer. Ver {@link StepRunner}.
 */
@Component
class StepJob implements JobHandler<StepJob.Payload> {

    static final String TYPE = "campaign.step";

    private final StepRunner runner;
    private final CampaignProperties properties;

    StepJob(StepRunner runner, CampaignProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    /** @param stepCode código da etapa ou {@code REPLY} (ação de resposta) */
    record Payload(UUID enrollmentId, String stepCode, int iteration) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return properties.stepMaxAttempts();
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        runner.run(context, payload, maxAttempts());
    }
}
