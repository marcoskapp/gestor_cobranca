package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.DisputeContactCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.storage.MalwareScanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contestações pela API pública: o credor consulta os pedidos da assessoria e responde (escopo
 * {@code disputes:write}); token de outro credor não enxerga a contestação; sem o escopo, 403.
 */
@Import(PublicApiDisputesIT.ScannerConfig.class)
class PublicApiDisputesIT extends PublicApiTestSupport {

    /** Verificador de teste: o marcador EICAR no conteúdo é "malware". */
    @TestConfiguration
    static class ScannerConfig {
        @Bean
        @Primary
        MalwareScanner testScanner() {
            return new MalwareScanner() {
                @Override
                public ScanResult scan(java.io.InputStream content) {
                    try {
                        String text = new String(content.readAllBytes(), StandardCharsets.ISO_8859_1);
                        return text.contains("EICAR") ? new ScanResult(Verdict.INFECTED, "Eicar-Test-Signature")
                                : new ScanResult(Verdict.CLEAN, null);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public String engine() {
                    return "test";
                }
            };
        }
    }

    private HttpResponse<String> respondWithFiles(String token, UUID disputeId, String json, Map<String, byte[]> files)
            throws IOException, InterruptedException {
        String boundary = "----gc" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"response\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + json + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + f.getKey()
                    + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(f.getValue());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/public-api/v1/disputes/" + disputeId + "/creditor-responses"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Autowired
    DebtIngestionPort ingestion;
    @Autowired
    CaseUseCases cases;
    @Autowired
    DisputeUseCases disputes;
    @Autowired
    CreditorDisputeContactUseCases contacts;
    @Autowired
    ApplicationContext context;

    private DisputeView disputeWithRequest() {
        UUID debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord("52998224725", "Devedor API",
                LocalDate.of(1980, 1, 1), List.of(new ContactRecord("EMAIL", "devedor.api@exemplo.com", null, false)), null))
                .id());
        UUID batch = Ids.newId();
        system(() -> {
            ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtor, "T-API-1", null, 1,
                    LocalDate.of(2026, 3, 10), new BigDecimal("250.00"), null, null), batch);
            ingestion.completeImport(new ImportSummary(batch, creditorA, portfolioA, 1, 0, 1));
            return null;
        });
        long deadline = System.currentTimeMillis() + 60_000;
        while (system(() -> cases.findActive(debtor, portfolioA)).isEmpty() && System.currentTimeMillis() < deadline) {
            JobDrain.drain(context, jdbc, "collection.");
        }
        UUID caseId = system(() -> cases.findActive(debtor, portfolioA)).orElseThrow().id();
        system(() -> contacts.create(creditorA, new DisputeContactCommand(null, "Contestações", "contestacoes@a.test",
                null, "PRIMARY")));
        DisputeView dispute = asManager(() -> disputes.open(caseId, new OpenDisputeCommand(Set.of(), "WRONG_AMOUNT",
                "DEBTOR", "Juros acima do contrato", null, null)));
        var draft = asManager(() -> disputes.draftCreditorRequest(dispute.id()));
        asManager(() -> disputes.requestCreditor(dispute.id(), new CreditorRequestCommand(draft.subject(), draft.body(),
                null, Set.of(), Set.of(), false)));
        return dispute;
    }

    @Test
    void creditorReadsRequestsAndAnswersThroughTheApi() throws Exception {
        DisputeView dispute = disputeWithRequest();
        String tokenA = issue(creditorA, allScopes(), null, null).token();
        String readOnly = issue(creditorA, Set.of("disputes:read"), null, null).token();
        String tokenB = issue(creditorB, allScopes(), null, null).token();

        HttpResponse<String> requests = get("/public-api/v1/disputes/" + dispute.id() + "/creditor-requests", tokenA);
        assertThat(requests.statusCode()).isEqualTo(200);
        assertThat(requests.body()).contains("\"sequenceNumber\":1").contains("memória de cálculo")
                .contains("\"requestedDueDate\"");
        assertThat(body(get("/public-api/v1/disputes/" + dispute.id(), tokenA)).get("awaitingCreditor")).isEqualTo(true);

        String answer = "{\"position\":\"PARTIAL\",\"note\":\"Juros recalculados conforme contrato\"}";
        assertThat(call("POST", "/public-api/v1/disputes/" + dispute.id() + "/creditor-responses", readOnly, answer,
                Map.of()).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/public-api/v1/disputes/" + dispute.id() + "/creditor-responses", tokenB, answer,
                Map.of()).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/public-api/v1/disputes/" + dispute.id() + "/creditor-responses", tokenA,
                "{\"position\":\"MAYBE\",\"note\":\"x\"}", Map.of()).statusCode()).isEqualTo(400);

        HttpResponse<String> created = call("POST", "/public-api/v1/disputes/" + dispute.id() + "/creditor-responses",
                tokenA, answer, Map.of());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(body(created)).containsEntry("source", "PUBLIC_API").containsEntry("position", "PARTIAL");
        assertThat(body(get("/public-api/v1/disputes/" + dispute.id(), tokenA)).get("awaitingCreditor")).isEqualTo(false);
        assertThat(get("/public-api/v1/disputes/" + dispute.id() + "/creditor-requests", tokenA).body())
                .contains("\"status\":\"ANSWERED\"");
    }

    @Test
    void creditorSendsDocumentsThatGoThroughQuarantineBeforeReachingTheAgency() throws Exception {
        DisputeView dispute = disputeWithRequest();
        String token = issue(creditorA, allScopes(), null, null).token();
        String answer = "{\"position\":\"CONFIRMS_DEBT\",\"note\":\"Contrato e extrato anexos\"}";
        byte[] contract = "%PDF-1.4 contrato assinado".getBytes(StandardCharsets.US_ASCII);
        byte[] infected = "%PDF-1.4 X5O!P%@AP EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII);

        // conteúdo que não é PDF/PNG/JPEG é recusado, qualquer que seja a extensão
        HttpResponse<String> fake = respondWithFiles(token, dispute.id(), answer,
                Map.of("planilha.pdf", "MZ executavel".getBytes(StandardCharsets.US_ASCII)));
        assertThat(fake.statusCode()).isEqualTo(422);
        assertThat(fake.body()).contains("dispute.evidence_type_invalid");

        HttpResponse<String> sent = respondWithFiles(token, dispute.id(), answer,
                new java.util.LinkedHashMap<>(Map.of("contrato.pdf", contract, "extrato.pdf", infected)));
        assertThat(sent.statusCode()).isEqualTo(201);
        assertThat(body(sent)).containsEntry("documentsInVerification", 2).containsEntry("documentsAccepted", 0);
        // nada chega à assessoria antes da verificação
        assertThat(asManager(() -> disputes.listEvidence(dispute.id()))).isEmpty();

        JobDrain.drain(context, jdbc, "collection.");
        assertThat(asManager(() -> disputes.listEvidence(dispute.id()))).singleElement()
                .satisfies(e -> assertThat(e.fileName()).isEqualTo("contrato.pdf"));
        String listed = get("/public-api/v1/disputes/" + dispute.id() + "/creditor-responses", token).body();
        assertThat(listed).contains("\"documentsAccepted\":1").contains("\"documentsRejected\":1")
                .contains("\"documentsInVerification\":0");
        assertThat(system(() -> jdbc.queryForObject("select count(*) from shared.access_log where event_type ="
                + " 'MALWARE_DETECTED'", Long.class))).isEqualTo(1);
    }
}
