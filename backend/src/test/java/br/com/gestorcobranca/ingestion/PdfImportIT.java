package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.ApproveCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfRecordView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordDecisionCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.SaveTemplateCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UpdateMapCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UploadPdfCommand;
import br.com.gestorcobranca.ingestion.application.port.out.PdfModelPort;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.support.pdf.FakeDocuments;
import br.com.gestorcobranca.ingestion.support.pdf.PdfFixtures;
import br.com.gestorcobranca.shared.ai.LanguageModel;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo da importação PDF inteligente: envio → mapa proposto pela IA (modelo dublê, nada é chamado fora) →
 * validação e edição do mapa → aprovação → lote canônico na conferência do ingestion; mapa salvo reaplicado sem IA;
 * divergência de totais exige justificativa; PDF escaneado lido visualmente com conferência reforçada; IA
 * indisponível leva ao mapa manual.
 */
@Import(PdfImportIT.FakeModelConfig.class)
class PdfImportIT extends IngestionTestSupport {

    private static final Set<Permission> IMPORTER = EnumSet.of(Permission.IMPORT_VIEW, Permission.IMPORT_EXECUTE,
            Permission.DEBTOR_VIEW);
    private static final Set<Permission> VIEWER = EnumSet.of(Permission.IMPORT_VIEW);

    @Autowired
    PdfImportUseCases pdfImports;
    @Autowired
    ImportReviewUseCases reviews;
    @Autowired
    FakePdfModel model;

    private final UUID ana = Ids.newId();

    /** Modelo dublê: devolve as respostas programadas, na ordem, e conta as chamadas. */
    static final class FakePdfModel implements PdfModelPort {

        final Deque<Object> responses = new ArrayDeque<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile LanguageModel.ModelRequest lastRequest;

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            Object next = responses.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next == null) {
                throw new IllegalStateException("Resposta do modelo não programada");
            }
            String[] toolAndArgs = (String[]) next;
            return new ModelResponse(null, List.of(new ToolCall("call-" + calls.get(), toolAndArgs[0], toolAndArgs[1])),
                    StopReason.TOOL_USE, new Usage(1200, 300), new Cost(new BigDecimal("0.004500"), "USD"),
                    "fake-model", Duration.ofMillis(50));
        }

        void willPropose(String toolName, String argumentsJson) {
            responses.add(new String[]{toolName, argumentsJson});
        }

        void willFail(RuntimeException e) {
            responses.add(e);
        }
    }

    @TestConfiguration
    static class FakeModelConfig {
        @Bean
        FakePdfModel fakePdfModel() {
            return new FakePdfModel();
        }
    }

    private <T> T asAna(Supplier<T> action) {
        return asUser(ana, IMPORTER, creditorA, action);
    }

    private PdfImportView upload(byte[] pdf, String name) {
        return asAna(() -> pdfImports.upload(new UploadPdfCommand(portfolioA, name, "application/pdf", pdf.length,
                () -> new ByteArrayInputStream(pdf), "SCREEN", null)));
    }

    private PdfImportView get(UUID id) {
        return asAna(() -> pdfImports.get(id));
    }

    private List<PdfRecordView> records(UUID id) {
        return asAna(() -> pdfImports.records(id, null, PageQuery.of(0, 200)).content());
    }

    private String mapJson(ExtractionMap map) {
        return json.toJson(map);
    }

    @Test
    void uploadMapApproveAndIngestionReviewThenReuseSavedMap() {
        model.responses.clear();
        model.willPropose("registrar_mapa", mapJson(PdfFixtures.historyMap()));
        int callsBefore = model.calls.get();
        byte[] pdf = PdfFixtures.history(PdfFixtures.historyTitles(12), null);
        PdfImportView received = upload(pdf, "historico.pdf");
        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(received.textLayer()).isTrue();

        runJobs();
        PdfImportView mapped = get(received.id());
        assertThat(mapped.status()).isEqualTo("AWAITING_VALIDATION");
        assertThat(mapped.mapOrigin()).isEqualTo("AI");
        assertThat(mapped.mapRevision()).isEqualTo(1);
        assertThat(mapped.summary().records()).isEqualTo(12);
        assertThat(mapped.summary().totalsMatch()).isTrue();
        assertThat(mapped.totals()).hasSize(2).allMatch(t -> t.matches());
        assertThat(mapped.blockers()).isEmpty();
        assertThat(mapped.explanation()).anyMatch(l -> l.contains("Pendente") && l.contains("saldo"));
        assertThat(mapped.usage().calls()).isEqualTo(1);
        assertThat(mapped.usage().inputTokens()).isEqualTo(1200);
        assertThat(mapped.usage().cost()).isEqualByComparingTo("0.0045");
        assertThat(model.calls.get()).isEqualTo(callsBefore + 1);
        // o modelo recebeu o texto mascarado: nenhum dígito do documento do devedor nem o e-mail
        String sent = model.lastRequest.messages().getFirst().content();
        assertThat(sent).doesNotContain(PdfFixtures.HISTORY_CNPJ.substring(0, 8)).doesNotContain("contato@exemplo.test")
                .contains("CPF/CNPJ:").contains("99.999.999/9999-99");

        List<PdfRecordView> rows = records(received.id());
        assertThat(rows).hasSize(12);
        assertThat(rows.getFirst().masked()).isFalse();
        assertThat(rows.getFirst().values()).containsEntry("documento", FakeDocuments.maskedCnpj(PdfFixtures.HISTORY_CNPJ))
                .containsEntry("saldo", "455.63");
        assertThat(rows.getFirst().sources().get("saldo")).isNotEmpty();
        assertThat(count("select count(*) from shared.access_log where tenant_id = ? and event_type = 'SENSITIVE_DATA_VIEWED'",
                tenant)).isPositive();
        // só leitura: valores pessoais mascarados e sem imagem da página
        List<PdfRecordView> masked = asUser(Ids.newId(), VIEWER, creditorA,
                () -> pdfImports.records(received.id(), null, PageQuery.of(0, 5)).content());
        assertThat(masked.getFirst().masked()).isTrue();
        assertThat(masked.getFirst().values().get("documento")).doesNotContain(PdfFixtures.HISTORY_CNPJ.substring(2, 8));
        assertThat(asAna(() -> pdfImports.page(received.id(), 1)).content()).startsWith((byte) 0x89);

        // edição do mapa: ignorar as linhas do 2º formato de pagamento derruba os totais (reprocessa na hora)
        ExtractionMap edited = new ExtractionMap(1, "x", ExtractionMap.NumberStyle.COMMA, PdfFixtures.historyMap().debtor(),
                PdfFixtures.historyMap().documentFields(), PdfFixtures.historyMap().table(),
                PdfFixtures.historyMap().controlTotals(), List.of("Página", "Boleto Fic"));
        PdfImportView broken = asAna(() -> pdfImports.updateMap(received.id(),
                new UpdateMapCommand(json.toMap(json.toJson(edited)), 1)));
        assertThat(broken.mapOrigin()).isEqualTo("USER");
        assertThat(broken.mapRevision()).isEqualTo(2);
        assertThat(broken.summary().records()).isEqualTo(6);
        assertThat(broken.summary().totalsMatch()).isFalse();
        assertThat(broken.totalsJustificationRequired()).isTrue();
        assertThatThrownBy(() -> asAna(() -> pdfImports.approve(received.id(), new ApproveCommand(2, null, false))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "pdf_import.totals_justification_required");
        // revisão desatualizada é recusada
        assertThatThrownBy(() -> asAna(() -> pdfImports.approve(received.id(), new ApproveCommand(1, null, false))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "pdf_import.revision_changed");

        // volta ao mapa correto, exclui um título e aprova
        PdfImportView fixed = asAna(() -> pdfImports.updateMap(received.id(),
                new UpdateMapCommand(json.toMap(mapJson(PdfFixtures.historyMap())), 2)));
        assertThat(fixed.summary().totalsMatch()).isTrue();
        PdfImportView decided = asAna(() -> pdfImports.decideRecords(received.id(),
                new RecordDecisionCommand(List.of(12), "EXCLUDE", "Título já negociado diretamente", 3)));
        assertThat(decided.summary().recordsToImport()).isEqualTo(11);
        PdfImportView approved = asAna(() -> pdfImports.approve(received.id(), new ApproveCommand(3, null, false)));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.reviewId()).isNotNull();
        assertThat(approved.approvedBy()).isEqualTo(ana);
        assertThat(approved.revisions()).extracting(r -> r.origin()).containsExactly("AI", "USER", "USER");

        // o lote canônico segue a conferência do ingestion: mesma validação de sempre
        runJobs();
        ReviewView review = asAna(() -> reviews.get(approved.reviewId()));
        assertThat(review.batch().fileName()).isEqualTo("historico-pdf-r3.csv");
        assertThat(review.batch().status()).isEqualTo("VALIDATED");
        assertThat(review.batch().counters().rowsRead()).isEqualTo(11);
        assertThat(review.batch().counters().rowsValid()).isEqualTo(11);
        assertThat(count("select count(*) from shared.business_event where tenant_id = ? and event_type = 'pdf_import.imported'",
                tenant)).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? and action = 'pdf_import.approved'",
                tenant)).isEqualTo(1);

        // mesmo PDF de novo na carteira: recusado (idempotência pelo hash)
        assertThatThrownBy(() -> upload(pdf, "historico-copia.pdf"))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "pdf_import.duplicate_file");

        // mapa salvo para o credor: o próximo PDF do mesmo layout é mapeado sem IA
        var template = asAna(() -> pdfImports.saveTemplate(received.id(), new SaveTemplateCommand("Histórico financeiro", 3)));
        assertThat(template.version()).isEqualTo(1);
        int callsAfterFirst = model.calls.get();
        PdfImportView second = upload(PdfFixtures.history(PdfFixtures.historyTitles(5), null), "historico-2.pdf");
        runJobs();
        PdfImportView reused = get(second.id());
        assertThat(reused.mapOrigin()).isEqualTo("TEMPLATE");
        assertThat(reused.templateId()).isEqualTo(template.id());
        assertThat(reused.summary().records()).isEqualTo(5);
        assertThat(reused.summary().totalsMatch()).isTrue();
        assertThat(model.calls.get()).isEqualTo(callsAfterFirst);
        assertThat(count("select count(*) from shared.business_event where tenant_id = ? and event_type = 'pdf_import.map_reused'",
                tenant)).isEqualTo(1);
    }

    @Test
    void scannedPdfIsReadVisuallyWithReinforcedConfirmation() {
        model.responses.clear();
        String doc = FakeDocuments.maskedCpf(FakeDocuments.cpf(201));
        model.willPropose("registrar_leitura", json.toJson(Map.of(
                "documentType", "Carteira digitalizada", "numberFormat", "COMMA",
                "columns", List.of(Map.of("header", "Documento", "target", "documento"), Map.of("header", "Valor", "target", "valor_principal")),
                "records", List.of(
                        Map.of("page", 1, "confidence", 0.97, "values", Map.of("documento", doc, "nome", "PEDRO FICTICIO",
                                "id_titulo", "V-1", "vencimento", "10/09/2026", "valor_principal", "350,00")),
                        Map.of("page", 1, "confidence", 0.6, "values", Map.of("documento", doc, "nome", "PEDRO FICTICIO",
                                "id_titulo", "V-2", "vencimento", "10/10/2026", "valor_principal", "150,00"))),
                "controls", List.of(Map.of("label", "Total:", "target", "valor_principal", "value", "R$ 500,00",
                        "scope", "DOCUMENT", "page", 1)))));
        PdfImportView received = upload(PdfFixtures.scanned(), "digitalizado.pdf");
        assertThat(received.textLayer()).isFalse();
        runJobs();
        PdfImportView read = get(received.id());
        assertThat(read.status()).isEqualTo("AWAITING_VALIDATION");
        assertThat(read.mapOrigin()).isEqualTo("VISUAL");
        assertThat(read.summary().records()).isEqualTo(2);
        assertThat(read.summary().lowConfidence()).isEqualTo(1);
        assertThat(read.summary().totalsMatch()).isTrue();
        assertThat(read.visualConfirmationRequired()).isTrue();
        assertThat(read.blockers()).anyMatch(b -> b.contains("confiança baixa"));
        assertThat(model.lastRequest.messages().getFirst().attachments()).hasSize(1);
        assertThat(records(received.id())).allMatch(r -> r.origin().equals("VISUAL"));
        // leitura visual não tem mapa editável
        assertThatThrownBy(() -> asAna(() -> pdfImports.updateMap(received.id(),
                new UpdateMapCommand(json.toMap(mapJson(PdfFixtures.simpleMap())), 1))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "pdf_import.visual_map_not_editable");

        asAna(() -> pdfImports.decideRecords(received.id(),
                new RecordDecisionCommand(List.of(2), "CONFIRM", "Conferido com a imagem da página", 1)));
        assertThatThrownBy(() -> asAna(() -> pdfImports.approve(received.id(), new ApproveCommand(1, null, false))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "pdf_import.visual_confirmation_required");
        PdfImportView approved = asAna(() -> pdfImports.approve(received.id(), new ApproveCommand(1, null, true)));
        assertThat(approved.status()).isEqualTo("APPROVED");
        runJobs();
        assertThat(asAna(() -> reviews.get(approved.reviewId())).batch().counters().rowsValid()).isEqualTo(2);
    }

    @Test
    void unavailableModelLeadsToManualMapAndDiscard() {
        model.responses.clear();
        model.willFail(new LanguageModel.ModelRejectedException("acesso negado", null));
        PdfImportView received = upload(PdfFixtures.simpleTable(PdfFixtures.simpleTitles()), "carteira.pdf");
        runJobs();
        PdfImportView waiting = get(received.id());
        assertThat(waiting.status()).isEqualTo("AWAITING_MAP");
        assertThat(waiting.failureCode()).isEqualTo("pdf_map.ai_unavailable");

        PdfImportView manual = asAna(() -> pdfImports.updateMap(received.id(),
                new UpdateMapCommand(json.toMap(mapJson(PdfFixtures.simpleMap())), 0)));
        assertThat(manual.status()).isEqualTo("AWAITING_VALIDATION");
        assertThat(manual.mapOrigin()).isEqualTo("USER");
        assertThat(manual.summary().records()).isEqualTo(3);
        assertThat(manual.summary().totalsMatch()).isTrue();

        PdfImportView discarded = asAna(() -> pdfImports.discard(received.id(), "Credor vai reenviar em planilha"));
        assertThat(discarded.status()).isEqualTo("DISCARDED");
        // descartado: o mesmo PDF pode ser enviado de novo
        model.willPropose("registrar_mapa", mapJson(PdfFixtures.simpleMap()));
        assertThat(upload(PdfFixtures.simpleTable(PdfFixtures.simpleTitles()), "carteira.pdf").status()).isEqualTo("RECEIVED");
    }

    @Test
    void otherCreditorCannotSeeTheImport() {
        model.responses.clear();
        model.willPropose("registrar_mapa", mapJson(PdfFixtures.simpleMap()));
        PdfImportView received = upload(PdfFixtures.simpleTable(PdfFixtures.simpleTitles()), "outro.pdf");
        assertThatThrownBy(() -> asUser(Ids.newId(), IMPORTER, creditorB, () -> pdfImports.get(received.id())))
                .isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);
    }
}
