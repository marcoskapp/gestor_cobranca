package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.debt.application.port.in.TitleAmountStatisticsPort;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountStatisticsPort.AmountStatistics;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountStatisticsPort.Distribution;
import br.com.gestorcobranca.ingestion.application.ReviewProperties;
import br.com.gestorcobranca.ingestion.application.port.out.RowValueStore;
import br.com.gestorcobranca.ingestion.application.port.out.RowValueStore.AnalysisRow;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertFinding;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.AmountReference;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReview;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReviewAlert;
import br.com.gestorcobranca.ingestion.domain.model.review.LogScaleStats;
import br.com.gestorcobranca.ingestion.domain.model.review.ReferenceKind;
import br.com.gestorcobranca.ingestion.domain.model.review.AnalysisStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.ReviewStatus;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewAlertRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewRepository;
import br.com.gestorcobranca.ingestion.domain.service.review.AlertSettings;
import br.com.gestorcobranca.ingestion.domain.service.review.AmountOutlierPolicy;
import br.com.gestorcobranca.ingestion.domain.service.review.ReviewSignals;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Análise de valores da prévia de conferência ({@code docs/tecnico/desenho/importacao.md} §4): "ticket médio" por
 * estatística robusta em escala logarítmica, com referência no histórico do credor (módulo debt) ou no próprio
 * arquivo, e os demais sinais (saldo, vencimento, devedor, contrato, centavos, escala do arquivo). Executada por job
 * ({@link ReviewAnalysisJobHandler}) depois que a prévia termina de validar; lê os valores em fluxo (duas passagens) e
 * guarda no máximo {@code max-alerts} alertas — os de maior prioridade.
 */
@Component
class ReviewAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewAnalyzer.class);
    private static final int MAX_PRODUCTS = 50;
    private static final int SQL_SIGNAL_LIMIT = 5000;

    private final ImportReviewRepository reviews;
    private final ImportReviewAlertRepository alerts;
    private final RowValueStore rowValues;
    private final TitleAmountStatisticsPort history;
    private final BusinessEventLogger businessEvents;
    private final IngestionMetrics metrics;
    private final ReviewProperties properties;
    private final JsonCodec json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    ReviewAnalyzer(ImportReviewRepository reviews, ImportReviewAlertRepository alerts, RowValueStore rowValues,
                   TitleAmountStatisticsPort history, BusinessEventLogger businessEvents, IngestionMetrics metrics,
                   ReviewProperties properties, JsonCodec json, Clock clock, PlatformTransactionManager transactionManager) {
        this.reviews = reviews;
        this.alerts = alerts;
        this.rowValues = rowValues;
        this.history = history;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    void analyze(UUID reviewId, UUID batchId) {
        Timer.Sample sample = metrics.start();
        ImportReview review = readTx.execute(s -> reviews.findById(reviewId).orElse(null));
        if (review == null || review.getStatus() != ReviewStatus.OPEN || !batchId.equals(review.getCurrentBatchId())
                || review.getAnalysisStatus() != AnalysisStatus.PENDING) {
            return;
        }
        AlertSettings settings = properties.alertSettings();
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));

        // 1ª passagem: distribuição do próprio arquivo (total e por produto)
        FileDistribution file = new FileDistribution();
        readTx.executeWithoutResult(s -> rowValues.forEachValid(batchId, file::add));
        LogScaleStats fileStats = LogScaleStats.of(file.all.toArray());
        Map<String, LogScaleStats> fileByProduct = new HashMap<>();
        file.byProduct.forEach((product, values) -> {
            if (values.size() >= settings.minSample()) {
                fileByProduct.put(product, LogScaleStats.of(values.toArray()));
            }
        });
        List<String> products = file.byProduct.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, DoubleList> e) -> e.getValue().size()).reversed())
                .limit(MAX_PRODUCTS).map(Map.Entry::getKey).toList();
        AmountStatistics hist = readTx.execute(s -> history.statistics(review.getCreditorId(), review.getPortfolioId(),
                products));
        References references = new References(settings.minSample(), stats(hist.creditor()), stats(hist.portfolio()),
                toStats(hist.byProduct()), fileStats, fileByProduct);

        // 2ª passagem: alertas por linha
        AmountOutlierPolicy policy = new AmountOutlierPolicy(settings);
        ReviewSignals signals = new ReviewSignals(settings);
        Collector collector = new Collector(properties.maxAlerts());
        readTx.executeWithoutResult(s -> rowValues.forEachValid(batchId, row -> {
            AmountReference reference = references.choose(row.product());
            policy.evaluate(row.rowNumber(), "valor_principal", row.principal(), reference)
                    .ifPresent(f -> collector.add(f, row.externalId()));
            signals.balance(row.rowNumber(), row.principal(), row.balance(), row.dueDate(), today)
                    .ifPresent(f -> collector.add(f, row.externalId()));
            signals.dueDate(row.rowNumber(), row.dueDate(), today).ifPresent(f -> collector.add(f, row.externalId()));
        }));
        readTx.executeWithoutResult(s -> {
            for (RowValueStore.DebtorOutlier o : rowValues.debtorOutliers(batchId, settings.debtorSpreadRatio(),
                    SQL_SIGNAL_LIMIT)) {
                signals.debtorSpread(o.rowNumber(), o.principal(), o.debtorMedian())
                        .ifPresent(f -> collector.add(f, o.externalId()));
            }
            for (RowValueStore.ContractSum c : rowValues.contractCandidates(batchId, settings.contractOverRatio(),
                    SQL_SIGNAL_LIMIT)) {
                signals.contract(c.firstRow(), c.contractNumber(), c.lines(), c.installments(), c.contractAmount())
                        .ifPresent(f -> collector.add(f, c.firstExternalId()));
            }
        });

        // sinais do arquivo como um todo
        LogScaleStats historyStats = references.historyBaseline();
        Distribution historyDistribution = hist.portfolio().size() >= settings.minSample() ? hist.portfolio() : hist.creditor();
        signals.fileShift(fileStats, historyStats).ifPresent(f -> collector.add(f, null));
        signals.cents(file.integerShare(), file.all.size(), historyDistribution.integerShare(), historyDistribution.size())
                .ifPresent(f -> collector.add(f, null));

        Map<String, Object> summary = summary(references, file, hist, collector);
        String summaryJson = json.toJson(summary);
        List<Pending> kept = collector.sorted();
        boolean truncated = collector.total > kept.size();
        Boolean saved = tx.execute(s -> {
            ImportReview fresh = reviews.findById(reviewId).orElse(null);
            if (fresh == null || !fresh.analysisFinished(batchId, collector.total, truncated, summaryJson, Instant.now(clock))) {
                return false;
            }
            alerts.saveAll(kept.stream().map(p -> new ImportReviewAlert(reviewId, batchId, p.externalId(), p.finding()))
                    .toList());
            reviews.save(fresh);
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_ALERTS_DETECTED)
                    .creditor(fresh.getCreditorId()).portfolio(fresh.getPortfolioId())
                    .reference("import_review", reviewId)
                    .attr("batchId", batchId)
                    .attr("alerts", collector.total)
                    .attr("stored", kept.size())
                    .attr("truncated", truncated)
                    .attr("byType", collector.byTypeNames())
                    .attr("reference", summary.get("references"))
                    .attr("rowsAnalyzed", file.all.size()));
            return true;
        });
        if (Boolean.TRUE.equals(saved)) {
            collector.byType.forEach((type, count) -> metrics.alertsDetected(type.name(), count));
            metrics.analysis(sample, "done");
            LOG.atInfo().addKeyValue("reviewId", reviewId).addKeyValue("batchId", batchId)
                    .addKeyValue("alerts", collector.total).addKeyValue("rows", file.all.size())
                    .log("Análise de valores da conferência concluída");
        } else {
            metrics.analysis(sample, "discarded");
        }
    }

    /** Falha persistente: a conferência mostra a falha e o usuário pode tentar de novo (refazer a prévia). */
    void fail(UUID reviewId, UUID batchId) {
        tx.executeWithoutResult(s -> reviews.findById(reviewId).ifPresent(review -> {
            review.analysisFailed(batchId);
            reviews.save(review);
        }));
    }

    private static Map<String, Object> summary(References references, FileDistribution file, AmountStatistics hist,
                                               Collector collector) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("references", references.used);
        summary.put("fileRows", file.all.size());
        summary.put("fileMedian", median(references.file));
        summary.put("historyCreditorSize", hist.creditor().size());
        summary.put("historyPortfolioSize", hist.portfolio().size());
        summary.put("historyMedian", median(references.historyBaseline()));
        summary.put("fileIntegerShare", file.integerShare());
        summary.put("byType", collector.byTypeNames());
        return summary;
    }

    private static BigDecimal median(LogScaleStats stats) {
        return stats == null || stats.size() == 0 ? null
                : BigDecimal.valueOf(stats.median()).setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    private static LogScaleStats stats(Distribution d) {
        return d == null || d.size() == 0 ? null : new LogScaleStats(d.size(), d.medianLog(), d.madLog());
    }

    private static Map<String, LogScaleStats> toStats(Map<String, Distribution> byProduct) {
        Map<String, LogScaleStats> map = new HashMap<>();
        byProduct.forEach((product, d) -> {
            LogScaleStats s = stats(d);
            if (s != null) {
                map.put(product, s);
            }
        });
        return map;
    }

    /** Escolha da referência por linha, na ordem de preferência, com amostra mínima ({@code importacao.md} §4.2). */
    private static final class References {

        private final int minSample;
        private final LogScaleStats creditor;
        private final LogScaleStats portfolio;
        private final Map<String, LogScaleStats> historyByProduct;
        private final LogScaleStats file;
        private final Map<String, LogScaleStats> fileByProduct;
        private final Map<String, Long> used = new LinkedHashMap<>();

        References(int minSample, LogScaleStats creditor, LogScaleStats portfolio,
                   Map<String, LogScaleStats> historyByProduct, LogScaleStats file, Map<String, LogScaleStats> fileByProduct) {
            this.minSample = minSample;
            this.creditor = creditor;
            this.portfolio = portfolio;
            this.historyByProduct = historyByProduct;
            this.file = file;
            this.fileByProduct = fileByProduct;
        }

        AmountReference choose(String product) {
            AmountReference ref = pick(product);
            if (ref != null) {
                used.merge(ref.kind().name(), 1L, Long::sum);
            }
            return ref;
        }

        private AmountReference pick(String product) {
            if (product != null && enough(historyByProduct.get(product))) {
                return new AmountReference(ReferenceKind.HISTORY_PRODUCT, historyByProduct.get(product));
            }
            if (enough(portfolio)) {
                return new AmountReference(ReferenceKind.HISTORY_PORTFOLIO, portfolio);
            }
            if (enough(creditor)) {
                return new AmountReference(ReferenceKind.HISTORY_CREDITOR, creditor);
            }
            if (product != null && enough(fileByProduct.get(product))) {
                return new AmountReference(ReferenceKind.FILE_PRODUCT, fileByProduct.get(product));
            }
            return enough(file) ? new AmountReference(ReferenceKind.FILE, file) : null;
        }

        /** Histórico mais específico com amostra suficiente (para os sinais do arquivo como um todo). */
        LogScaleStats historyBaseline() {
            return enough(portfolio) ? portfolio : enough(creditor) ? creditor : null;
        }

        private boolean enough(LogScaleStats stats) {
            return stats != null && stats.size() >= minSample;
        }
    }

    /** Valores do arquivo na primeira passagem (só {@code double}: ~8 bytes por linha). */
    private static final class FileDistribution {

        private final DoubleList all = new DoubleList();
        private final Map<String, DoubleList> byProduct = new HashMap<>();
        private long integers;

        void add(AnalysisRow row) {
            double value = row.principal().doubleValue();
            all.add(value);
            if (row.product() != null) {
                byProduct.computeIfAbsent(row.product(), p -> new DoubleList()).add(value);
            }
            if (row.principal().stripTrailingZeros().scale() <= 0) {
                integers++;
            }
        }

        double integerShare() {
            return all.size() == 0 ? 0 : (double) integers / all.size();
        }
    }

    /** Lista de {@code double} sem caixas. */
    private static final class DoubleList {

        private double[] values = new double[256];
        private int size;

        void add(double value) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        int size() {
            return size;
        }

        double[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }

    private record Pending(AlertFinding finding, String externalId) {
    }

    /** Mantém os {@code max} alertas de maior prioridade; conta todos por tipo. */
    private static final class Collector {

        private static final Comparator<Pending> WEAKEST_FIRST = Comparator
                .comparingInt((Pending p) -> p.finding().priority())
                .thenComparing(Comparator.comparingLong((Pending p) -> p.finding().rowNumber()).reversed());

        private final int max;
        private final PriorityQueue<Pending> heap;
        private final Map<AlertType, Long> byType = new EnumMap<>(AlertType.class);
        private int total;

        Collector(int max) {
            this.max = Math.max(1, max);
            this.heap = new PriorityQueue<>(WEAKEST_FIRST);
        }

        void add(AlertFinding finding, String externalId) {
            total++;
            byType.merge(finding.type(), 1L, Long::sum);
            heap.add(new Pending(finding, externalId));
            if (heap.size() > max) {
                heap.poll();
            }
        }

        List<Pending> sorted() {
            List<Pending> list = new ArrayList<>(heap);
            list.sort(WEAKEST_FIRST.reversed());
            return list;
        }

        Map<String, Long> byTypeNames() {
            Map<String, Long> map = new LinkedHashMap<>();
            byType.forEach((type, count) -> map.put(type.name(), count));
            return map;
        }
    }
}
