package br.com.gestorcobranca.campaign.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Parâmetros do módulo campaign ({@code app.campaign.*}).
 *
 * @param enrollmentBatch   casos avaliados por job de inscrição (o restante segue em jobs de continuação)
 * @param sweepInterval     intervalo da varredura que inscreve novos casos dos segmentos das campanhas ativas
 * @param maintenanceInterval intervalo da manutenção (retomadas programadas, fim do controle, etapas atrasadas)
 * @param stepMaxAttempts   tentativas do job de etapa em falha técnica antes de marcar a inscrição com falha
 * @param reconcileGrace    atraso a partir do qual uma etapa ativa sem job é reenfileirada
 * @param maintenanceBatch  inscrições por lote de manutenção
 * @param audienceBatch     casos por job de reconstrução do read model de audiência
 * @param audienceCron      reconciliação diária do read model de audiência
 */
@ConfigurationProperties(prefix = "app.campaign")
public record CampaignProperties(Integer enrollmentBatch, Duration sweepInterval, Duration maintenanceInterval,
                                 Integer stepMaxAttempts, Duration reconcileGrace, Integer maintenanceBatch,
                                 Integer audienceBatch, String audienceCron) {

    public CampaignProperties {
        enrollmentBatch = enrollmentBatch == null ? 200 : enrollmentBatch;
        sweepInterval = sweepInterval == null ? Duration.ofMinutes(5) : sweepInterval;
        maintenanceInterval = maintenanceInterval == null ? Duration.ofMinutes(5) : maintenanceInterval;
        stepMaxAttempts = stepMaxAttempts == null ? 5 : stepMaxAttempts;
        reconcileGrace = reconcileGrace == null ? Duration.ofMinutes(15) : reconcileGrace;
        maintenanceBatch = maintenanceBatch == null ? 500 : maintenanceBatch;
        audienceBatch = audienceBatch == null ? 200 : audienceBatch;
        audienceCron = audienceCron == null ? "0 15 3 * * *" : audienceCron;
    }
}
