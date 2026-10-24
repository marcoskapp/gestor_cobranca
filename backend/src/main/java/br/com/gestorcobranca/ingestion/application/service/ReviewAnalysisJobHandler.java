package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Job da análise de valores de uma prévia de conferência ({@link ReviewAnalyzer}). Idempotente: análise de prévia que
 * deixou de ser a corrente, ou já concluída, é descartada. Esgotadas as tentativas, a conferência registra a falha.
 */
@Component
class ReviewAnalysisJobHandler implements JobHandler<ReviewAnalysisJobHandler.Payload> {

    static final String TYPE = "ingestion.review-analysis";
    private static final Logger LOG = LoggerFactory.getLogger(ReviewAnalysisJobHandler.class);

    private final ReviewAnalyzer analyzer;

    ReviewAnalysisJobHandler(ReviewAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    record Payload(UUID reviewId, UUID batchId) {
    }

    static String deduplicationKey(UUID batchId) {
        return "review-analysis:" + batchId;
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
        return 4;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        try {
            analyzer.analyze(payload.reviewId(), payload.batchId());
        } catch (RuntimeException e) {
            if (context.attempt() < maxAttempts()) {
                throw e;
            }
            LOG.atError().addKeyValue("reviewId", payload.reviewId()).addKeyValue("batchId", payload.batchId())
                    .setCause(e).log("Análise de valores da conferência falhou em todas as tentativas");
            analyzer.fail(payload.reviewId(), payload.batchId());
        }
    }
}
