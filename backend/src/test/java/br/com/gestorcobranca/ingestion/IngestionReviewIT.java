package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ControlTotalsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportRecordsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AlertDecisionCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AlertView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ApprovalCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.BreakdownRowView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ColumnFormatView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.CommitCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.FormatCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.StartReviewCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.TotalsCheckView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.TotalsCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo completo da conferência da importação ({@code docs/tecnico/desenho/importacao.md}): formato numérico
 * misturado barrado até a decisão, totais divergentes bloqueiam (com a razão 10ⁿ), alertas de valor revisados liberam,
 * linha excluída não é gravada, aprovação de segunda pessoa e as mesmas regras na API.
 */
class IngestionReviewIT extends IngestionTestSupport {

    private static final Set<Permission> IMPORTER = EnumSet.of(Permission.IMPORT_VIEW, Permission.IMPORT_EXECUTE,
            Permission.DEBTOR_VIEW);
    private static final Set<Permission> APPROVER = EnumSet.of(Permission.IMPORT_VIEW, Permission.IMPORT_APPROVE);

    @Autowired
    ImportReviewUseCases reviews;

    private final UUID ana = Ids.newId();

    private <T> T asAna(Supplier<T> action) {
        return asUser(ana, IMPORTER, creditorA, action);
    }

    private ReviewView start(UUID portfolioId, UUID creditorId, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return asUser(ana, IMPORTER, creditorId, () -> reviews.start(new StartReviewCommand(portfolioId, null,
                "carteira.csv", "text/csv", bytes.length, () -> new ByteArrayInputStream(bytes))));
    }

    private ReviewView review(UUID id) {
        return asAna(() -> reviews.get(id));
    }

    private static String file(String... lines) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (String l : lines) {
            csv.append(l).append('\n');
        }
        return csv.toString();
    }

    private static String row(int seed, String externalId, String principal) {
        return line(cpf(seed), "Devedor " + seed, externalId, principal, null);
    }

    @Test
    void mixedSeparatorsAreBlockedUntilTheUserDecides() {
        ReviewView started = start(portfolioA, creditorA, file(row(1, "M-1", "1.234,56"), row(2, "M-2", "1,234.56"),
                row(3, "M-3", "1.000"), row(4, "M-4", "10")));
        runJobs();
        ReviewView paused = review(started.id());
        assertThat(paused.batch().status()).isEqualTo("AWAITING_FORMAT");
        assertThat(paused.batch().counters().rowsRead()).isZero();
        assertThat(paused.format().awaitingDecision()).isTrue();
        ColumnFormatView principal = paused.format().columns().stream().filter(c -> c.column().equals("valor_principal"))
                .findFirst().orElseThrow();
        assertThat(principal.status()).isEqualTo("MIXED");
        assertThat(principal.decision()).isNull();
        assertThat(principal.options()).extracting(o -> o.option())
                .containsExactly("COMMA", "DOT", "PER_VALUE_GROUPING", "PER_VALUE_DECIMAL");
        // exemplos reais convertidos lado a lado: "1.000" vira mil ou um real conforme a escolha
        assertThat(principal.options().get(2).examples()).anySatisfy(e -> {
            assertThat(e.raw()).isEqualTo("1.000");
            assertThat(e.value()).isEqualByComparingTo("1000");
        });
        assertThat(principal.options().get(3).examples()).anySatisfy(e -> {
            assertThat(e.raw()).isEqualTo("1.000");
            assertThat(e.value()).isEqualByComparingTo("1");
        });
        assertThat(principal.options().get(2).total()).isEqualByComparingTo("3479.12");
        assertThat(paused.blockers()).extracting(b -> b.code()).containsExactly("FORMAT_PENDING");

        // nada avança sem a decisão
        assertThatThrownBy(() -> asAna(() -> reviews.checkTotals(started.id(), new TotalsCommand(4L, null, null, null))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> asAna(() -> reviews.confirmFormat(started.id(), new FormatCommand(null, Map.of()))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "import_review.format_decision_required");

        ReviewView decided = asAna(() -> reviews.confirmFormat(started.id(),
                new FormatCommand(paused.batch().id(), Map.of("valor_principal", "PER_VALUE_GROUPING"))));
        assertThat(decided.batch().id()).isEqualTo(paused.batch().id());
        assertThat(decided.batch().status()).isEqualTo("VALIDATING");
        assertThat(decided.format().confirmed()).isTrue();
        runJobs();
        ReviewView validated = review(started.id());
        assertThat(validated.batch().status()).isEqualTo("VALIDATED");
        assertThat(validated.batch().numberFormat()).containsEntry("valor_principal", "PER_VALUE_GROUPING");
        assertThat(validated.batch().counters().principalTotal()).isEqualByComparingTo("3479.12");
        assertThat(validated.analysis().status()).isEqualTo("DONE");
        assertThat(count("select count(*) from shared.business_event where tenant_id = ? and event_type = 'import.format_confirmed'",
                tenant)).isEqualTo(1);

        // mudar de ideia depois de validar cria outra prévia do mesmo arquivo
        ReviewView changed = asAna(() -> reviews.confirmFormat(started.id(),
                new FormatCommand(null, Map.of("valor_principal", "PER_VALUE_DECIMAL"))));
        assertThat(changed.batch().id()).isNotEqualTo(paused.batch().id());
        assertThat(changed.previews()).isEqualTo(2);
        assertThat(changed.batch().reprocessOfBatchId()).isEqualTo(paused.batch().id());
        runJobs();
        assertThat(review(started.id()).batch().counters().principalTotal()).isEqualByComparingTo("2480.12");
    }

    @Test
    void divergentTotalsBlockAndPointToTheDecimalSeparator() {
        ReviewView started = start(portfolioA, creditorA, file(row(1, "T-1", "1.000"), row(2, "T-2", "2.000"),
                row(3, "T-3", "500")));
        runJobs();
        ReviewView paused = review(started.id());
        assertThat(paused.format().columns().getFirst().status()).isEqualTo("AMBIGUOUS");
        // o usuário escolhe ponto decimal ("1.000" = R$ 1,00), mas a planilha dele soma R$ 3.500,00
        asAna(() -> reviews.confirmFormat(started.id(), new FormatCommand(null, Map.of("valor_principal", "DOT"))));
        runJobs();
        assertThatThrownBy(() -> asAna(() -> reviews.breakdown(started.id())))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "import_review.totals_not_checked");

        TotalsCheckView mismatch = asAna(() -> reviews.checkTotals(started.id(),
                new TotalsCommand(3L, new BigDecimal("3500.00"), null, null)));
        assertThat(mismatch.matched()).isFalse();
        assertThat(mismatch.measures()).filteredOn(m -> m.measure().equals("PRINCIPAL")).singleElement().satisfies(m -> {
            assertThat(m.computed()).isEqualByComparingTo("503.00");
            assertThat(m.matches()).isFalse();
        });
        assertThat(mismatch.hints()).extracting(h -> h.code()).contains("ALTERNATIVE_MATCH");
        assertThat(mismatch.suspectRows()).isNotEmpty().first().satisfies(r -> assertThat(r.ambiguous()).isTrue());
        ReviewView blocked = review(started.id());
        assertThat(blocked.blockers()).extracting(b -> b.code()).contains("TOTALS_MISMATCH");
        assertThatThrownBy(() -> asAna(() -> reviews.commit(started.id(), new CommitCommand("IMPORTAR", null))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "import_review.blocked");
        assertThat(count("select count(*) from shared.business_event where tenant_id = ? and event_type = 'import.totals_mismatch'",
                tenant)).isEqualTo(1);

        // razão 10ⁿ explícita: 1.000 lido como 1.000.000? (planilha soma 503, arquivo com vírgula soma 3.500)
        TotalsCheckView scale = asAna(() -> reviews.checkTotals(started.id(),
                new TotalsCommand(null, new BigDecimal("5030.00"), null, null)));
        assertThat(scale.measures().getFirst().scaleExponent()).isEqualTo(1);
        assertThat(scale.hints()).extracting(h -> h.code()).contains("SCALE");

        TotalsCheckView ok = asAna(() -> reviews.checkTotals(started.id(),
                new TotalsCommand(3L, new BigDecimal("503.00"), null, null)));
        assertThat(ok.matched()).isTrue();
        ReviewView matched = review(started.id());
        assertThat(matched.totals().matched()).isTrue();
        assertThat(matched.totals().checks()).isEqualTo(3);
        assertThat(matched.totals().mismatches()).isEqualTo(2);
        List<BreakdownRowView> breakdown = asAna(() -> reviews.breakdown(started.id()));
        assertThat(breakdown).filteredOn(r -> r.dimension().equals("VALIDITY") && r.key().equals("VALID"))
                .singleElement().satisfies(r -> assertThat(r.rows()).isEqualTo(3));
        assertThat(breakdown).anySatisfy(r -> assertThat(r.dimension()).isEqualTo("AGING"));
        assertThat(count("select count(*) from ingestion.import_totals_check where tenant_id = ?", tenant)).isEqualTo(3);
    }

    @Test
    void reviewedOutliersReleaseTheImportAndExcludedRowsAreNotWritten() {
        // histórico do credor na carteira: 40 títulos de R$ 100,00 a R$ 139,00
        StringBuilder history = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < 40; i++) {
            history.append(row(100 + i, "H-" + i, (100 + i) + ",00")).append('\n');
        }
        ImportBatchView loaded = uploadCsv("historico.csv", history.toString(), "IMPORT");
        runJobs();
        assertThat(batch(loaded.id()).status()).isEqualTo("COMPLETED");

        // arquivo novo: "12000,00" (cem vezes o ticket) é provável separador decimal
        ReviewView started = start(portfolioA, creditorA, file(row(1, "N-1", "110,00"), row(2, "N-2", "125,50"),
                row(3, "N-3", "12000,00"), row(4, "N-4", "118,00")));
        runJobs();
        ReviewView validated = review(started.id());
        assertThat(validated.batch().status()).isEqualTo("VALIDATED");
        assertThat(validated.format().columns().getFirst().status()).isEqualTo("DETECTED");
        assertThat(validated.format().confirmed()).isFalse();
        assertThat(validated.analysis().status()).isEqualTo("DONE");
        List<AlertView> alerts = asAna(() -> reviews.alerts(started.id(), null, null, PageQuery.of(0, 50)).content());
        assertThat(alerts).first().satisfies(a -> {
            assertThat(a.type()).isEqualTo("DECIMAL_SCALE");
            assertThat(a.rowNumber()).isEqualTo(4);
            assertThat(a.externalId()).isEqualTo("N-3");
            assertThat(a.reference()).isEqualTo("HISTORY_PORTFOLIO");
            assertThat(a.suggestedAmount()).isEqualByComparingTo("120.00");
        });
        asAna(() -> reviews.confirmFormat(started.id(), new FormatCommand(null, null)));
        asAna(() -> reviews.checkTotals(started.id(), new TotalsCommand(4L, new BigDecimal("12353.50"), null, null)));
        ReviewView pending = review(started.id());
        assertThat(pending.blockers()).extracting(b -> b.code()).containsExactly("SCALE_ALERTS_OPEN");
        assertThat(pending.impact().rowsToImport()).isEqualTo(4);
        assertThat(pending.impact().newTitles()).isEqualTo(4);

        AlertView scale = alerts.getFirst();
        assertThatThrownBy(() -> asAna(() -> reviews.decideAlerts(started.id(), new AlertDecisionCommand(
                List.of(scale.id()), false, null, "EXCLUDE", "")))).isInstanceOf(BusinessRuleException.class);
        var analysis = asAna(() -> reviews.decideAlerts(started.id(), new AlertDecisionCommand(List.of(scale.id()), false,
                null, "EXCLUDE", "Valor em centavos na origem; credor vai reenviar")));
        assertThat(analysis.rowsExcluded()).isEqualTo(1);
        assertThat(analysis.openScale()).isZero();
        // demais alertas (se houver) confirmados em lote
        asAna(() -> reviews.decideAlerts(started.id(), new AlertDecisionCommand(List.of(), true, null, "CONFIRM",
                "Conferido com o credor")));
        ReviewView ready = review(started.id());
        assertThat(ready.blockers()).isEmpty();
        assertThat(ready.impact().rowsToImport()).isEqualTo(3);
        assertThat(ready.impact().rowsExcluded()).isEqualTo(1);
        assertThat(ready.impact().principalToImport()).isEqualByComparingTo("353.50");

        assertThatThrownBy(() -> asAna(() -> reviews.commit(started.id(), new CommitCommand("importa", null))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import_review.confirmation_mismatch");
        ReviewView committed = asAna(() -> reviews.commit(started.id(), new CommitCommand("importar", null)));
        assertThat(committed.status()).isEqualTo("COMMITTED");
        UUID importId = committed.commit().importBatchId();
        runJobs();
        ImportBatchView imported = batch(importId);
        assertThat(imported.status()).isEqualTo("COMPLETED");
        assertThat(imported.mode()).isEqualTo("IMPORT");
        assertThat(imported.reviewId()).isEqualTo(started.id());
        assertThat(imported.reprocessOfBatchId()).isEqualTo(validated.batch().id());
        assertThat(imported.counters().rowsExcluded()).isEqualTo(1);
        assertThat(imported.counters().titlesCreated()).isEqualTo(3);
        assertThat(imported.counters().principalTotal()).isEqualByComparingTo("353.50");
        assertThat(allTitles(creditorA)).extracting(t -> t.externalId()).contains("N-1", "N-2", "N-4").doesNotContain("N-3");
        assertThat(issues(importId)).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("row.excluded_by_review");
            assertThat(i.rowNumber()).isEqualTo(4);
        });
        assertThat(count("select count(*) from shared.business_event where tenant_id = ? and event_type = 'import.outliers_reviewed'",
                tenant)).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? and action = 'import_review.alerts_decided'",
                tenant)).isGreaterThanOrEqualTo(1);
        // nada da conferência muda depois de efetivada
        assertThatThrownBy(() -> asAna(() -> reviews.moveTo(started.id(), 2))).isInstanceOf(ConflictException.class);
    }

    @Test
    void importAboveThresholdNeedsASecondPerson() {
        String csv = file(row(1, "A-1", "400.000,00"), row(2, "A-2", "350.000,00"), row(3, "A-3", "300.000,00"));
        ReviewView started = start(portfolioB, creditorB, csv);
        runJobs();
        Supplier<ReviewView> refresh = () -> asUser(ana, IMPORTER, creditorB, () -> reviews.get(started.id()));
        asUser(ana, IMPORTER, creditorB, () -> reviews.confirmFormat(started.id(), new FormatCommand(null, null)));
        asUser(ana, IMPORTER, creditorB, () -> reviews.checkTotals(started.id(),
                new TotalsCommand(3L, new BigDecimal("1050000.00"), null, null)));
        assertThat(refresh.get().blockers()).isEmpty();
        assertThat(refresh.get().impact().approvalRequired()).isTrue();

        UUID bruno = Ids.newId();
        ReviewView waiting = asUser(bruno, IMPORTER, creditorB, () -> reviews.commit(started.id(),
                new CommitCommand("IMPORTAR", null)));
        assertThat(waiting.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(waiting.commit().importBatchId()).isNull();
        assertThat(waiting.canApprove()).isFalse();
        // nada muda enquanto aguarda
        assertThatThrownBy(() -> asUser(ana, IMPORTER, creditorB, () -> reviews.checkTotals(started.id(),
                new TotalsCommand(3L, null, null, null)))).isInstanceOf(ConflictException.class);
        // quem enviou ou pediu a efetivação não aprova, mesmo com a permissão
        Set<Permission> both = EnumSet.of(Permission.IMPORT_VIEW, Permission.IMPORT_EXECUTE, Permission.IMPORT_APPROVE);
        assertThatThrownBy(() -> asUser(bruno, both, creditorB, () -> reviews.approve(started.id(),
                new ApprovalCommand("IMPORTAR", null)))).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "import_review.self_approval");
        assertThatThrownBy(() -> asUser(ana, both, creditorB, () -> reviews.approve(started.id(),
                new ApprovalCommand("IMPORTAR", null)))).isInstanceOf(ConflictException.class);
        // sem a permissão
        assertThatThrownBy(() -> asUser(Ids.newId(), IMPORTER, creditorB, () -> reviews.approve(started.id(),
                new ApprovalCommand("IMPORTAR", null)))).isInstanceOf(RuntimeException.class);

        UUID carla = Ids.newId();
        assertThat(asUser(carla, APPROVER, creditorB, () -> reviews.get(started.id())).canApprove()).isTrue();
        ReviewView approved = asUser(carla, APPROVER, creditorB, () -> reviews.approve(started.id(),
                new ApprovalCommand("IMPORTAR", "Conferido com o credor por telefone")));
        assertThat(approved.status()).isEqualTo("COMMITTED");
        assertThat(approved.commit().decidedBy()).isEqualTo(carla);
        runJobs();
        assertThat(asUser(carla, APPROVER, creditorB, () -> batches.get(approved.commit().importBatchId())).status())
                .isEqualTo("COMPLETED");
        assertThat(titleCount(creditorB)).isEqualTo(3);
        assertThat(count("select count(*) from shared.business_event where tenant_id = ? and event_type = 'import.review_approved'",
                tenant)).isEqualTo(1);
    }

    @Test
    void apiAppliesTheSameNumericRulesAndOptionalControlTotals() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("documento", cpf(1));
        record.put("nome", "Maria");
        record.put("id_titulo", "API-1");
        record.put("vencimento", "2026-03-10");
        record.put("valor_principal", "1.000");
        // string ambígua sem decisão: falha antes de gravar
        ImportBatchView undecided = asImporterOf(creditorA, () -> batches.importRecords(
                new ImportRecordsCommand(portfolioA, null, false, null, List.of(record)), "erp-1"));
        runJobs();
        ImportBatchView failed = batch(undecided.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.statusReason()).contains("valor_principal");
        assertThat(issues(undecided.id())).anySatisfy(i -> assertThat(i.code()).isEqualTo("import.number_format_undecided"));
        assertThat(titleCount(creditorA)).isZero();

        // com a convenção declarada: mil reais
        ImportBatchView declared = asImporterOf(creditorA, () -> batches.importRecords(new ImportRecordsCommand(portfolioA,
                null, false, null, List.of(record), Map.of("valor_principal", "COMMA"), null), "erp-2"));
        runJobs();
        assertThat(batch(declared.id()).status()).isEqualTo("COMPLETED");
        assertThat(batch(declared.id()).numberFormat()).containsEntry("valor_principal", "COMMA");
        assertThat(title(creditorA, "API-1").principalAmount()).isEqualByComparingTo("1000.00");

        // número JSON: sem ambiguidade; totais de controle divergentes falham o lote
        Map<String, Object> numeric = new LinkedHashMap<>(record);
        numeric.put("id_titulo", "API-2");
        numeric.put("valor_principal", 1.5);
        ImportBatchView wrongTotals = asImporterOf(creditorA, () -> batches.importRecords(new ImportRecordsCommand(portfolioA,
                null, false, null, List.of(numeric), null, new ControlTotalsCommand(1L, new BigDecimal("150.00"), null, null)),
                "erp-3"));
        runJobs();
        ImportBatchView mismatch = batch(wrongTotals.id());
        assertThat(mismatch.status()).isEqualTo("FAILED");
        assertThat(issues(wrongTotals.id())).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo("import.control_totals_mismatch");
            assertThat(i.message()).contains("10^2");
        });
        ImportBatchView rightTotals = asImporterOf(creditorA, () -> batches.importRecords(new ImportRecordsCommand(portfolioA,
                null, false, null, List.of(numeric), null, new ControlTotalsCommand(1L, new BigDecimal("1.50"), null, null)),
                "erp-4"));
        runJobs();
        assertThat(batch(rightTotals.id()).status()).isEqualTo("COMPLETED");
        assertThat(title(creditorA, "API-2").principalAmount()).isEqualByComparingTo("1.50");

        // declaração que contradiz os valores ("1,5" prova vírgula decimal)
        Map<String, Object> comma = new LinkedHashMap<>(record);
        comma.put("id_titulo", "API-3");
        comma.put("valor_principal", "1,5");
        ImportBatchView conflict = asImporterOf(creditorA, () -> batches.importRecords(new ImportRecordsCommand(portfolioA,
                null, false, null, List.of(comma), Map.of("valor_principal", "DOT"), null), "erp-5"));
        runJobs();
        assertThat(issues(conflict.id())).anySatisfy(i -> assertThat(i.code()).isEqualTo("import.number_format_conflict"));
    }

    @Test
    void directFileImportWithAmbiguousColumnNeedsDecimalSeparator() {
        String csv = file(row(1, "D-1", "1.000"), row(2, "D-2", "2.500"));
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        ImportBatchView failing = upload(portfolioA, null, "a.csv", bytes, "IMPORT", false, null);
        runJobs();
        assertThat(batch(failing.id()).status()).isEqualTo("FAILED");
        assertThat(titleCount(creditorA)).isZero();
        ImportBatchView ok = asImporterOf(creditorA, () -> batches.uploadFile(new UploadFileCommand(portfolioA, null,
                "IMPORT", false, null, "b.csv", "text/csv", bytes.length, () -> new ByteArrayInputStream(bytes), "COMMA")));
        runJobs();
        assertThat(batch(ok.id()).status()).isEqualTo("COMPLETED");
        assertThat(batch(ok.id()).counters().principalTotal()).isEqualByComparingTo("3500.00");
    }

    @Test
    void reviewIsScopedByCreditorAndCanBeCancelled() {
        ReviewView started = start(portfolioA, creditorA, file(row(1, "S-1", "10,00")));
        assertThatThrownBy(() -> asUser(Ids.newId(), IMPORTER, creditorB, () -> reviews.get(started.id())))
                .isInstanceOf(RuntimeException.class);
        assertThat(asUser(Ids.newId(), IMPORTER, creditorB, () -> reviews.search(
                new ImportReviewUseCases.ReviewSearch(null, null, "OPEN"), PageQuery.of(0, 20))).content()).isEmpty();
        assertThat(asAna(() -> reviews.search(new ImportReviewUseCases.ReviewSearch(null, null, "OPEN,AWAITING_APPROVAL"),
                PageQuery.of(0, 20))).content()).singleElement().satisfies(s -> assertThat(s.fileName()).isEqualTo("carteira.csv"));
        ReviewView step = asAna(() -> reviews.moveTo(started.id(), 3));
        assertThat(step.currentStep()).isEqualTo(3);
        ReviewView cancelled = asAna(() -> reviews.cancel(started.id(), "Arquivo errado"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.batch().status()).isEqualTo("CANCELLED");
    }
}
