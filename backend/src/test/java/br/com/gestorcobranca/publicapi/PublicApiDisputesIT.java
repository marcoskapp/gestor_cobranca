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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

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
class PublicApiDisputesIT extends PublicApiTestSupport {

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
                LocalDate.of(1980, 1, 1), List.of(new ContactRecord("EMAIL", "devedor.api@exemplo.com", null, false))))
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
}
