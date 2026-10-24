package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Handlers dos jobs do módulo (executados pelo {@code JobWorker} no tenant do job, numa transação). */
final class ScoringJobHandlers {

    private ScoringJobHandlers() {
    }

    /** Página da escoragem em lote. */
    @Component
    static class BatchPageJob implements JobHandler<ScoreBatchService.PagePayload> {

        private final ScoreBatchService batch;

        BatchPageJob(ScoreBatchService batch) {
            this.batch = batch;
        }

        @Override
        public String type() {
            return ScoreBatchService.PAGE_JOB;
        }

        @Override
        public Class<ScoreBatchService.PagePayload> payloadType() {
            return ScoreBatchService.PagePayload.class;
        }

        @Override
        public void handle(JobContext context, ScoreBatchService.PagePayload payload) {
            batch.page(payload.runId());
        }
    }

    /** Monitoramento após uma escoragem com snapshot. */
    @Component
    static class MonitoringJob implements JobHandler<ScoreBatchService.MonitoringPayload> {

        private final MonitoringService monitoring;

        MonitoringJob(MonitoringService monitoring) {
            this.monitoring = monitoring;
        }

        @Override
        public String type() {
            return ScoreBatchService.MONITORING_JOB;
        }

        @Override
        public Class<ScoreBatchService.MonitoringPayload> payloadType() {
            return ScoreBatchService.MonitoringPayload.class;
        }

        @Override
        public void handle(JobContext context, ScoreBatchService.MonitoringPayload payload) {
            monitoring.evaluate(payload.runId());
        }
    }

    /** Treino da regressão logística. */
    @Component
    static class TrainingJob implements JobHandler<TrainingService.Payload> {

        private final TrainingService training;

        TrainingJob(TrainingService training) {
            this.training = training;
        }

        @Override
        public String type() {
            return TrainingService.JOB;
        }

        @Override
        public Class<TrainingService.Payload> payloadType() {
            return TrainingService.Payload.class;
        }

        @Override
        public int maxAttempts() {
            return 2;
        }

        @Override
        public void handle(JobContext context, TrainingService.Payload payload) {
            training.run(payload.runId());
        }
    }

    /** Recálculo de um caso após fato relevante (contato efetivo, promessa, acordo, pagamento). */
    @Component
    static class RescoreJob implements JobHandler<RescoreJob.Payload> {

        static final String TYPE = "scoring.rescore_case";

        record Payload(UUID caseId) {
        }

        private final CaseScoreService service;

        RescoreJob(CaseScoreService service) {
            this.service = service;
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
        public void handle(JobContext context, Payload payload) {
            service.rescoreByEvent(payload.caseId());
        }
    }
}
