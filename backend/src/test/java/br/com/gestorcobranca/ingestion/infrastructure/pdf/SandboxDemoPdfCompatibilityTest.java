package br.com.gestorcobranca.ingestion.infrastructure.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CheckReport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Extraction;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TotalsCheck;
import br.com.gestorcobranca.ingestion.domain.service.pdf.CanonicalAssembler;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionChecks;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionMapValidator;
import br.com.gestorcobranca.ingestion.domain.service.pdf.LayoutFingerprint;
import br.com.gestorcobranca.ingestion.domain.service.pdf.MapInterpreter;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdfLayouts;
import br.com.gestorcobranca.sandbox.domain.service.DemoPdfFactory;
import br.com.gestorcobranca.sandbox.domain.service.DemoPdfFactory.DemoPdfSet;
import br.com.gestorcobranca.sandbox.infrastructure.pdf.PdfBoxDemoPdfRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Os PDFs fictícios de demonstração do sandbox e os mapas do sandbox (o salvo do histórico e o gabarito da montagem
 * manual da tabela) funcionam com o leitor, o intérprete e as verificações reais da Importação PDF.
 */
class SandboxDemoPdfCompatibilityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PdfBoxDocumentReader reader = new PdfBoxDocumentReader();
    private final PdfBoxDemoPdfRenderer renderer = new PdfBoxDemoPdfRenderer();
    private final MapInterpreter interpreter = new MapInterpreter();
    private final CanonicalAssembler assembler = new CanonicalAssembler();
    private final ExtractionChecks checks = new ExtractionChecks(TODAY, 0.85);

    private record Result(PdfText text, List<CanonicalRecord> records, CheckReport report) {
    }

    static List<TenantSpec> tenants() {
        return DemoCatalog.tenants();
    }

    private static ExtractionMap map(Map<String, Object> json) {
        ExtractionMap map = JSON.convertValue(json, ExtractionMap.class);
        assertThat(new ExtractionMapValidator().validate(map)).isEmpty();
        return map;
    }

    private Result run(DemoPdf pdf, ExtractionMap map) {
        PdfText text = reader.inspect(renderer.render(pdf.document()), 50).text();
        assertThat(text.hasTextLayer()).isTrue();
        Extraction extraction = interpreter.interpret(text, map);
        List<CanonicalRecord> records = assembler.assemble(extraction, map);
        return new Result(text, records, checks.check(text, map, extraction, records, extraction.controls()));
    }

    private static List<Finding> errors(CheckReport report) {
        return report.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR).toList();
    }

    @ParameterizedTest
    @MethodSource("tenants")
    void savedHistoryMapReadsTheDemoPdfsAndBlocksTheDivergentOne(TenantSpec spec) {
        DemoPdfSet set = DemoPdfFactory.forTenant(spec, TODAY);
        ExtractionMap history = map(DemoPdfLayouts.historyMap());

        // o mapa é salvo a partir do modelo e reaproveitado (mesma impressão digital) nos PDFs do mesmo layout
        Result model = run(set.model(), history);
        assertThat(errors(model.report())).isEmpty();
        assertThat(model.report().totalsMatch()).isTrue();
        for (DemoPdf pdf : List.of(set.published().get(0), set.published().get(1), set.apiCheck())) {
            assertThat(LayoutFingerprint.matches(run(pdf, history).text(), history)).as(pdf.fileName()).isTrue();
        }

        DemoPdf.History demo = (DemoPdf.History) set.published().getFirst().document();
        Result ok = run(set.published().getFirst(), history);
        assertThat(ok.records()).hasSize(demo.titles().size());
        assertThat(errors(ok.report())).isEmpty();
        assertThat(ok.report().totals()).hasSize(2).allMatch(TotalsCheck::matches);
        CanonicalRecord first = ok.records().getFirst();
        assertThat(first.values()).containsEntry(CanonicalField.DEBTOR_NAME, demo.debtor().name())
                .containsEntry(CanonicalField.DEBTOR_DOCUMENT, demo.debtor().document())
                .containsEntry(CanonicalField.EMAIL_1, demo.debtor().email());
        for (int i = 0; i < demo.titles().size(); i++) {
            DemoPdf.HistoryTitle t = demo.titles().get(i);
            assertThat(ok.records().get(i).info()).as("colunas sem sobreposição no título " + i)
                    .containsEntry("forma_pagamento", t.payment()).containsEntry("nosso_numero", t.ourNumber())
                    .containsEntry("conta", t.account());
        }
        assertThat(sum(ok.records(), CanonicalField.REPORTED_BALANCE)).isEqualByComparingTo(demo.sumPending());

        Result divergent = run(set.published().get(1), history);
        assertThat(divergent.report().totalsMatch()).isFalse();
        assertThat(divergent.report().totals()).anySatisfy(t -> {
            assertThat(t.target()).isEqualTo("saldo");
            assertThat(t.matches()).isFalse();
            assertThat(t.difference().abs()).isEqualByComparingTo(DemoPdfFactory.DIVERGENCE);
        });

        assertThat(run(set.apiCheck(), history).records()).hasSize(DemoPdfFactory.API_CHECK_TITLES);
    }

    @ParameterizedTest
    @MethodSource("tenants")
    void tableLayoutIsNotTheSavedLayoutAndTheManualMapWorks(TenantSpec spec) {
        DemoPdf table = DemoPdfFactory.forTenant(spec, TODAY).published().get(2);
        ExtractionMap history = map(DemoPdfLayouts.historyMap());
        ExtractionMap manual = map(DemoPdfLayouts.tableMap());
        assertThat(LayoutFingerprint.matches(run(table, manual).text(), history)).isFalse();

        Result r = run(table, manual);
        DemoPdf.Table content = (DemoPdf.Table) table.document();
        assertThat(r.records()).hasSize(content.rows().size());
        assertThat(errors(r.report())).isEmpty();
        assertThat(r.report().totals()).hasSize(2).allMatch(TotalsCheck::matches);
    }

    @ParameterizedTest
    @MethodSource("tenants")
    void longHistoryBreaksPagesWithoutLosingTitles(TenantSpec spec) {
        DemoPdf.History base = (DemoPdf.History) DemoPdfFactory.forTenant(spec, TODAY).published().getFirst().document();
        List<DemoPdf.HistoryTitle> many = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (DemoPdf.HistoryTitle t : base.titles()) {
                many.add(new DemoPdf.HistoryTitle(t.company(), String.valueOf(Integer.parseInt(t.number()) + 100 * i),
                        t.installment(), t.issue(), t.due(), t.delay(), t.original(), t.pending(), t.interest(),
                        t.payment(), t.ourNumber(), t.account()));
            }
        }
        DemoPdf.History seed = new DemoPdf.History(base.issuedOn(), base.issuedAt(), base.debtor(), many,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        DemoPdf.History longer = new DemoPdf.History(base.issuedOn(), base.issuedAt(), base.debtor(), many,
                seed.sumOriginal(), seed.sumPending(), seed.sumInterest());
        Result r = run(new DemoPdf("longo.pdf", "teste", longer), map(DemoPdfLayouts.historyMap()));
        assertThat(r.text().pages()).hasSizeGreaterThan(1);
        assertThat(r.records()).hasSize(many.size());
        assertThat(r.report().totalsMatch()).isTrue();
    }

    private static BigDecimal sum(List<CanonicalRecord> records, CanonicalField field) {
        return records.stream().map(c -> new BigDecimal(c.values().get(field))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
