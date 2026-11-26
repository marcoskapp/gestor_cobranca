package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeStatusQueries;
import br.com.gestorcobranca.debt.application.port.in.AnonymizedSubjectQueries;
import br.com.gestorcobranca.debt.application.port.in.AnonymizedSubjectQueries.TitleReference;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.BatchFile;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.ExpiredFile;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.PendingErasure;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.SubjectRow;
import br.com.gestorcobranca.ingestion.domain.model.BlockEffect;
import br.com.gestorcobranca.ingestion.domain.model.BlockReason;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock;
import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock.Line;
import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock.MatchedBy;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReview;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewRepository;
import br.com.gestorcobranca.ingestion.domain.repository.PdfImportRepository;
import br.com.gestorcobranca.ingestion.domain.repository.SubjectBlockRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LGPD (SEG-20261124-D01, decisões 1460, 1469 e 1488–1493; ADR 0012): devedor anonimizado ({@code DebtorAnonymized}),
 * na transação do consumidor.
 * <ol>
 *   <li>Acha as linhas do titular nos lotes por duas chaves: o devedor gravado e o título do credor conservado no
 *       debt (pega também prévia, linha com erro e conferência recusada); e os lotes que compartilham o arquivo.</li>
 *   <li>Trava os lotes em ordem de id e os <strong>bloqueia</strong>: na fila, cancela na hora; em processamento,
 *       pede o cancelamento do sistema (para ao fim do bloco; o encerramento conclui a remoção do arquivo); encerrado,
 *       só marca. A conferência aberta é cancelada pelo sistema; o PDF de origem achado já foi aprovado e sai com o
 *       arquivo (o PDF ainda aberto não tem lote: resíduo da decisão 1493).</li>
 *   <li>Registra o bloqueio com as linhas do titular (referência mascarada) e o aviso ao credor do compliance.</li>
 *   <li>Remove o arquivo inteiro (mistura vários devedores) e o PDF de origem; o que ainda está aberto fica como
 *       remoção pendente. O armazenamento é o último passo: falha no banco desfaz tudo sem chave órfã.</li>
 * </ol>
 */
@Component
class IngestionAnonymizer {

    static final int PENDING_BATCH = 200;
    private static final String REASON = "anonimização do titular (LGPD)";
    private static final int TITLE_CHUNK = 500;

    private final ImportFileRetentionStore files;
    private final ImportBatchRepository batches;
    private final ImportReviewRepository reviews;
    private final PdfImportRepository pdfImports;
    private final SubjectBlockRepository subjectBlocks;
    private final AnonymizedSubjectQueries anonymizedSubjects;
    private final CreditorErasureNoticeStatusQueries notices;
    private final FileStorage storage;
    private final JobQueue jobQueue;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final IngestionMetrics metrics;
    private final Clock clock;

    IngestionAnonymizer(ImportFileRetentionStore files, ImportBatchRepository batches, ImportReviewRepository reviews,
                        PdfImportRepository pdfImports, SubjectBlockRepository subjectBlocks,
                        AnonymizedSubjectQueries anonymizedSubjects, CreditorErasureNoticeStatusQueries notices,
                        FileStorage storage, JobQueue jobQueue, AuditTrail auditTrail,
                        BusinessEventLogger businessEvents, IngestionMetrics metrics, Clock clock) {
        this.files = files;
        this.batches = batches;
        this.reviews = reviews;
        this.pdfImports = pdfImports;
        this.subjectBlocks = subjectBlocks;
        this.anonymizedSubjects = anonymizedSubjects;
        this.notices = notices;
        this.storage = storage;
        this.jobQueue = jobQueue;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    void anonymize(UUID debtorId, UUID sourceEventId) {
        Instant now = Instant.now(clock);
        Map<UUID, List<Line>> linesByBatch = subjectLines(debtorId);
        Map<UUID, List<Line>> targets = new TreeMap<>(linesByBatch);
        widen(targets, linesByBatch);
        Set<String> fileKeys = new LinkedHashSet<>();
        Set<UUID> blocked = new HashSet<>();
        Map<UUID, Optional<UUID>> noticeByCreditor = new HashMap<>();
        // todas as travas numa instrução, na ordem de id do banco; impasse raro (outra instrução que trave os mesmos
        // lotes noutra ordem) é desfeito pelo PostgreSQL e o consumidor tenta de novo. Depois das travas, procura de
        // novo: um lote criado com o mesmo arquivo enquanto isso (reprocessar, nova conferência) também entra
        for (int pass = 0; pass < 2; pass++) {
            List<UUID> pending = targets.keySet().stream().filter(id -> !blocked.contains(id)).toList();
            if (pending.isEmpty()) {
                break;
            }
            for (ImportBatch batch : batches.findAllByIdInForUpdate(pending)) {
                block(batch, targets.get(batch.getId()), debtorId, sourceEventId, now, noticeByCreditor);
                blocked.add(batch.getId());
                if (batch.getFileKey() != null) {
                    fileKeys.add(batch.getFileKey());
                }
            }
            widen(targets, linesByBatch);
        }

        List<String> keys = new ArrayList<>();
        int pending = 0;
        Set<UUID> pdfIds = new LinkedHashSet<>(files.pdfImportsWithDebtor(debtorId));
        pdfIds.addAll(files.pdfImportsOfBatches(targets.keySet()));
        for (UUID pdfId : pdfIds) {
            if (!erasePdf(pdfId, sourceEventId, now, keys)
                    && files.addPendingErasure(null, pdfId, sourceEventId, now)) {
                pending++;
            }
        }
        files.filesWithDebtor(debtorId).forEach(f -> fileKeys.add(f.fileKey()));
        for (String key : fileKeys) {
            if (!eraseFile(key, sourceEventId, null, now, keys) && files.addPendingErasure(key, null, sourceEventId, now)) {
                pending++;
            }
        }
        keys.forEach(storage::delete);
        metrics.filesDeleted(keys.size(), IngestionMetrics.REASON_ANONYMIZATION);
        metrics.erasuresPending(pending);
    }

    /**
     * Acrescenta os lotes que usam o mesmo arquivo de um lote com o titular: a mesma chave (nova conferência,
     * reprocessamento) ou o mesmo conteúdo do credor sob outra chave (reenvio) — com as mesmas linhas.
     */
    private void widen(Map<UUID, List<Line>> targets, Map<UUID, List<Line>> linesByBatch) {
        // só SQL: nenhuma entidade de lote entra no contexto antes da trava (a trava não a releria)
        for (BatchFile f : files.filesOfBatches(linesByBatch.keySet())) {
            List<Line> lines = linesByBatch.get(f.batchId());
            if (f.fileKey() != null) {
                files.batchesWithFileKey(f.fileKey()).forEach(o -> targets.putIfAbsent(o, lines));
            }
            files.batchesWithContent(f.creditorId(), f.fileSha256()).forEach(o -> targets.putIfAbsent(o, lines));
        }
    }

    /**
     * Linhas do titular por lote, pelas duas chaves (a do devedor prevalece na mesma linha), com a referência mascarada
     * e sem caracteres de controle — nunca o identificador cru.
     */
    private Map<UUID, List<Line>> subjectLines(UUID debtorId) {
        Map<UUID, Map<Long, SubjectRow>> byBatch = new TreeMap<>();
        for (SubjectRow row : files.rowsOfDebtor(debtorId)) {
            byBatch.computeIfAbsent(row.batchId(), k -> new TreeMap<>()).put(row.rowNumber(), row);
        }
        Map<UUID, List<String>> titlesByCreditor = anonymizedSubjects.referencesOf(debtorId).stream()
                .collect(Collectors.groupingBy(TitleReference::creditorId, LinkedHashMap::new,
                        Collectors.mapping(TitleReference::titleKey, Collectors.toList())));
        titlesByCreditor.forEach((creditorId, ids) -> {
            List<String> distinct = ids.stream().distinct().toList();
            for (int from = 0; from < distinct.size(); from += TITLE_CHUNK) {
                List<String> chunk = distinct.subList(from, Math.min(distinct.size(), from + TITLE_CHUNK));
                for (SubjectRow row : files.rowsOfTitles(creditorId, chunk)) {
                    byBatch.computeIfAbsent(row.batchId(), k -> new TreeMap<>()).putIfAbsent(row.rowNumber(), row);
                }
            }
        });
        Map<UUID, List<Line>> lines = new TreeMap<>();
        byBatch.forEach((batchId, rows) -> lines.put(batchId, rows.values().stream()
                .sorted(Comparator.comparingLong(SubjectRow::rowNumber))
                .map(r -> new Line(r.rowNumber(), display(r.externalId()),
                        r.byDebtor() ? MatchedBy.DEBTOR : MatchedBy.TITLE_REFERENCE))
                .toList()));
        return lines;
    }

    private static String display(String externalId) {
        return SubjectBlock.displayReference(externalId);
    }

    private void block(ImportBatch batch, List<Line> lines, UUID debtorId, UUID sourceEventId, Instant now,
                       Map<UUID, Optional<UUID>> noticeByCreditor) {
        UUID batchId = batch.getId();
        if (subjectBlocks.existsByBatchIdAndSourceEventId(batchId, sourceEventId)) {
            return;
        }
        String previousStatus = batch.getStatus().name();
        BlockEffect effect = batch.block(BlockReason.SUBJECT_ANONYMIZED, now, Actor.SYSTEM_ID);
        // o descarte do arquivo (SQL direto, mais adiante) também altera a linha: grava o bloqueio antes
        batches.saveAndFlush(batch);
        UUID closedReview = closeReview(batch, now);
        UUID noticeId = noticeByCreditor.computeIfAbsent(batch.getCreditorId(), c -> notices.noticeFor(debtorId, c))
                .orElse(null);
        if (effect == BlockEffect.CANCELLED_NOW) {
            // como o cancelamento na fila pela pessoa: o job do lote sai da fila e o encerramento conta
            jobQueue.cancelPending(ImportBatchJobHandler.TYPE, ImportBatchJobHandler.deduplicationKey(batchId, 0));
            metrics.finished(batch);
        }
        SubjectBlock block = subjectBlocks.save(new SubjectBlock(batchId, batch.getCreditorId(), noticeId,
                sourceEventId, effect, closedReview, lines));
        auditTrail.record(AuditEntry.of("import_batch.blocked_subject_anonymized", BatchCreation.RESOURCE, batchId)
                .creditor(batch.getCreditorId())
                .change("status", previousStatus, batch.getStatus().name())
                .meta("blockId", block.getId())
                .meta("effect", effect)
                .meta("lines", block.getLines())
                .meta("matchedByTitle", lines.stream().filter(l -> l.getMatchedBy() == MatchedBy.TITLE_REFERENCE).count())
                .meta("reviewId", closedReview)
                .meta("noticeId", noticeId)
                .meta("sourceEventId", sourceEventId)
                .reason(REASON));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_BATCH_BLOCKED)
                .creditor(batch.getCreditorId()).portfolio(batch.getPortfolioId())
                .reference("import_batch", batchId)
                .attr("effect", effect)
                .attr("lines", block.getLines()));
        metrics.batchBlocked(effect.name().toLowerCase(Locale.ROOT));
    }

    /** Conferência ainda aberta do lote (prévia corrente ou importação): cancelada pelo sistema. */
    private UUID closeReview(ImportBatch batch, Instant now) {
        if (batch.getReviewId() == null) {
            return null;
        }
        ImportReview review = reviews.findByIdForUpdate(batch.getReviewId()).orElse(null);
        if (review == null || review.getStatus().isTerminal()) {
            return null;
        }
        String previous = review.getStatus().name();
        review.cancel(Actor.SYSTEM_ID, now, ImportBatch.SUBJECT_BLOCK_REASON);
        // o descarte do arquivo confere por SQL direto se há conferência aberta: grava o cancelamento antes
        reviews.saveAndFlush(review);
        auditTrail.record(AuditEntry.of("import_review.closed_by_system", ImportReviewService.RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .change("status", previous, review.getStatus().name())
                .meta("batchId", batch.getId())
                .reason(REASON));
        return review.getId();
    }

    /**
     * Bloqueio achado na própria carga (decisão 1496): estende o bloqueio aos lotes do credor com o mesmo conteúdo sob
     * outra chave (SEG-20261126-M13, a mesma regra de {@link #widen}), travados numa instrução em ordem de id, fora da
     * transação do bloco da carga; os arquivos deles entram na remoção pendente. Na transação do chamador.
     */
    void extendFoundInLoad(UUID batchId) {
        Instant now = Instant.now(clock);
        List<BatchFile> self = files.filesOfBatches(List.of(batchId));
        if (self.isEmpty()) {
            return;
        }
        BatchFile origin = self.getFirst();
        Set<UUID> siblings = new java.util.TreeSet<>(files.batchesWithContent(origin.creditorId(), origin.fileSha256()));
        siblings.remove(batchId);
        if (siblings.isEmpty()) {
            return;
        }
        List<Line> lines = subjectBlocks.findByBatchIdOrderByCreatedAtAsc(batchId).stream()
                .flatMap(sb -> sb.getLineList().stream()).toList();
        UUID noticeId = subjectBlocks.findByBatchIdOrderByCreatedAtAsc(batchId).stream()
                .map(SubjectBlock::getNoticeId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        for (ImportBatch sibling : batches.findAllByIdInForUpdate(List.copyOf(siblings))) {
            if (sibling.isBlocked()) {
                continue;
            }
            // bloqueio completo: na fila, cancela; em processamento, para ao fim do bloco; encerrado, só marca
            BlockEffect effect = sibling.block(BlockReason.SUBJECT_ANONYMIZED, now, Actor.SYSTEM_ID);
            batches.saveAndFlush(sibling);
            if (effect == BlockEffect.CANCELLED_NOW) {
                jobQueue.cancelPending(ImportBatchJobHandler.TYPE, ImportBatchJobHandler.deduplicationKey(sibling.getId(), 0));
                metrics.finished(sibling);
            }
            subjectBlocks.save(SubjectBlock.foundInLoad(sibling.getId(), sibling.getCreditorId(), noticeId, lines));
            if (sibling.getFileKey() != null) {
                files.addPendingErasureOfBatch(sibling.getFileKey(), null, batchId, now);
            }
            auditTrail.record(AuditEntry.of("import_batch.blocked_subject_found", BatchCreation.RESOURCE, sibling.getId())
                    .creditor(sibling.getCreditorId())
                    .meta("effect", effect)
                    .meta("lines", lines.size())
                    .meta("originBatchId", batchId)
                    .reason("mesmo conteúdo de lote com linha de titular com dados eliminados (LGPD)"));
            metrics.batchBlocked(BlockEffect.FOUND_IN_LOAD.name().toLowerCase(Locale.ROOT));
        }
    }

    /** @param candidates pendências selecionadas (rodada cheia = pode haver mais) */
    record PendingRound(int candidates, int deleted) {
    }

    /**
     * Conclui uma rodada das remoções pendentes cujos lotes já encerraram (tenant corrente, na transação do chamador);
     * a métrica fica com o job.
     */
    PendingRound completePending() {
        Instant now = Instant.now(clock);
        List<String> keys = new ArrayList<>();
        // arquivos (lotes) antes dos PDFs, como no anonymize: a mesma ordem de travas
        List<PendingErasure> pending = files.pendingErasures(PENDING_BATCH).stream()
                .sorted(Comparator.comparing((PendingErasure p) -> p.pdfImportId() != null)).toList();
        for (PendingErasure p : pending) {
            boolean done = p.pdfImportId() != null
                    ? erasePdf(p.pdfImportId(), p.sourceEventId(), now, keys)
                    : eraseFile(p.fileKey(), p.sourceEventId(), p.originBatchId(), now, keys);
            if (done) {
                files.removePendingErasure(p.id());
            }
        }
        keys.forEach(storage::delete);
        return new PendingRound(pending.size(), keys.size());
    }

    /**
     * O PDF achado pela conferência do lote já foi aprovado (a ligação com a conferência só existe depois da aprovação);
     * o PDF ainda aberto não tem lote e fica com a retenção (resíduo da decisão 1493).
     *
     * @return falso se a importação PDF ainda está aberta (fica pendente)
     */
    private boolean erasePdf(UUID pdfId, UUID sourceEventId, Instant now, List<String> keys) {
        PdfImport pdf = pdfImports.findByIdForUpdate(pdfId).orElse(null);
        if (pdf == null || pdf.getFileKey() == null) {
            return true;
        }
        if (pdf.getStatus().open()) {
            return false;
        }
        keys.add(pdf.getFileKey());
        pdf.fileDeleted(now);
        pdfImports.save(pdf);
        auditTrail.record(AuditEntry.of("pdf_import.file_deleted", PdfImportService.RESOURCE, pdf.getId())
                .creditor(pdf.getCreditorId()).meta("fileSha256", pdf.getFileSha256())
                .meta("sourceEventId", sourceEventId).reason(REASON));
        return true;
    }

    /**
     * @param originBatchId lote que recusou a linha do titular na carga (decisão 1496); nulo quando vem do evento
     * @return falso se algum lote com o arquivo ainda está aberto (fica pendente)
     */
    private boolean eraseFile(String fileKey, UUID sourceEventId, UUID originBatchId, Instant now, List<String> keys) {
        List<ExpiredFile> current = files.fileByKey(fileKey);
        if (current.isEmpty()) {
            return true;
        }
        int marked = files.markDeleted(fileKey, now);
        if (marked == 0) {
            return false;
        }
        ExpiredFile file = current.getFirst();
        keys.add(fileKey);
        AuditEntry audit = AuditEntry.of("import_batch.file_deleted", BatchCreation.RESOURCE, file.batchId())
                .creditor(file.creditorId()).meta("batches", marked).meta("fileSha256", file.fileSha256())
                .reason(REASON);
        auditTrail.record(originBatchId != null ? audit.meta("originBatchId", originBatchId)
                : audit.meta("sourceEventId", sourceEventId));
        return true;
    }
}
