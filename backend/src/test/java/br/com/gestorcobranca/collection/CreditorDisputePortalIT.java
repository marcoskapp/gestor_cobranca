package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.creditor.CreditorDisputeUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.DisputeDetail;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.RespondCommand;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.ResponseItem;
import br.com.gestorcobranca.creditorportal.application.port.in.CreditorDisputePortalUseCases.Upload;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases.DisputeContactCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contestações no portal do credor (ADR 0011, fatia 5): o credor vê só as contestações dele em que a assessoria pediu a
 * posição dele (as demais respondem 404, com {@code SCOPE_DENIED} quando são de outro credor), baixa os documentos do
 * pedido com registro, e responde com documentos que passam pela quarentena. A resposta tem a origem
 * {@code CREDITOR_PORTAL}, o instante do servidor, encerra o "aguardando o credor" e nunca resolve a contestação.
 */
@Import(DisputeWorkflowIT.MailConfig.class)
class CreditorDisputePortalIT extends CollectionTestSupport {

    private static final Set<Permission> READ_ONLY = Set.of(Permission.CREDITOR_PORTAL_OVERVIEW);
    private static final Set<Permission> LEGAL = Set.of(Permission.CREDITOR_PORTAL_OVERVIEW,
            Permission.CREDITOR_DISPUTE_RESPOND);

    @Autowired
    CreditorDisputeContactUseCases disputeContacts;
    @Autowired
    CreditorDisputePortalUseCases portal;
    @Autowired
    CreditorDisputeUseCases creditorDisputes;
    @Autowired
    tools.jackson.databind.json.JsonMapper json;

    private final UUID creditorUser = Ids.newId();
    private final UUID session = Ids.newId();

    @Test
    void creditorSeesRequestedDisputesDownloadsAndRespondsThroughQuarantineWithoutResolving() throws Exception {
        provision("crdisp");
        system(() -> disputeContacts.create(creditorA, new DisputeContactCommand(null, "Equipe de contestações",
                "contestacoes@bancoa.test", null, "PRIMARY")));
        DisputeView dispute = openDispute(creditorA);
        DisputeView withoutRequest = openDispute(creditorA);
        asSupervisor(() -> disputes.assign(dispute.id(), operatorX, "Analista"));
        var evidence = asOperator(operatorX, () -> disputes.attachEvidence(dispute.id(), new EvidenceUpload(
                "comprovante.pdf", "application/pdf", br.com.gestorcobranca.support.TestPdfs.blank().length, pdf())));
        CreditorRequestDraft draft = asOperator(operatorX, () -> disputes.draftCreditorRequest(dispute.id()));
        CreditorRequestView request = asOperator(operatorX, () -> disputes.requestCreditor(dispute.id(),
                new CreditorRequestCommand(draft.subject() + " {{link_evidencias}}", draft.body(), null, Set.of(),
                        Set.of(evidence.id()), false)));
        drainJobs();

        // a lista mostra só a contestação com pedido; sem pedido (ou de outro credor) não existe para o credor
        var list = asCreditor(creditorA, READ_ONLY, portal::disputes);
        assertThat(list).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(dispute.id());
            assertThat(item.situation()).isEqualTo("AWAITING_YOU");
            assertThat(item.requestedDueDate()).isEqualTo(request.requestedDueDate());
            assertThat(item.protocol()).hasSize(8);
        });
        assertThatThrownBy(() -> asCreditor(creditorA, READ_ONLY, () -> portal.dispute(withoutRequest.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        long denied = count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED'");
        assertThatThrownBy(() -> asCreditor(creditorB, READ_ONLY, () -> portal.dispute(dispute.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED'"))
                .isEqualTo(denied + 1);
        assertThat(asCreditor(creditorB, READ_ONLY, portal::disputes)).isEmpty();
        // a porta do credor não atende a assessoria
        assertThatThrownBy(() -> asSupervisor(creditorDisputes::disputes)).isInstanceOf(ScopeAccessDeniedException.class);

        DisputeDetail detail = asCreditor(creditorA, READ_ONLY, () -> portal.dispute(dispute.id()));
        assertThat(detail.titles()).isNotEmpty();
        assertThat(detail.requests()).singleElement().satisfies(r -> {
            assertThat(r.awaiting()).isTrue();
            // o texto como foi enviado, sem o link seguro (o token é segredo do e-mail; os documentos estão na ficha)
            assertThat(r.subject()).doesNotContain("dispute-files");
            assertThat(r.body()).contains("protocolo " + item(dispute.id())).doesNotContain("dispute-files")
                    .contains("os documentos estão na ficha desta contestação");
            assertThat(r.documents()).extracting(d -> d.fileName()).containsExactly("comprovante.pdf");
        });

        // download do documento do pedido, registrado com a origem portal; documento que não foi ao credor → 404
        var doc = detail.requests().getFirst().documents().getFirst();
        try (InputStream in = asCreditor(creditorA, READ_ONLY, () -> portal.download(dispute.id(), doc.id())).content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.US_ASCII)).startsWith("%PDF");
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' "
                + "and details ->> 'origin' = 'creditor_portal' and details ->> 'resource' = 'dispute_evidence'"))
                .isEqualTo(1);
        var internalOnly = asOperator(operatorX, () -> disputes.attachEvidence(dispute.id(), new EvidenceUpload(
                "nota-interna.pdf", "application/pdf", br.com.gestorcobranca.support.TestPdfs.blank().length, pdf())));
        assertThatThrownBy(() -> asCreditor(creditorA, READ_ONLY, () -> portal.download(dispute.id(), internalOnly.id())))
                .isInstanceOf(ResourceNotFoundException.class);

        // perfil só de consulta não responde
        RespondCommand answer = new RespondCommand(request.id(), "ACCEPTS_CLAIM", "Pagamento localizado, segue o extrato");
        assertThatThrownBy(() -> asCreditor(creditorA, READ_ONLY, () -> portal.respond(dispute.id(), answer, List.of())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // mais de 5 documentos é recusado antes de gravar
        assertThatThrownBy(() -> asCreditor(creditorA, LEGAL, () -> portal.respond(dispute.id(), answer,
                Collections.nCopies(6, upload("x.pdf"))))).isInstanceOf(BusinessRuleException.class);

        // PDF com JavaScript é recusado antes de gravar (RS-39)
        byte[] active = br.com.gestorcobranca.support.TestPdfs.withJavaScript();
        assertThatThrownBy(() -> asCreditor(creditorA, LEGAL, () -> portal.respond(dispute.id(), answer,
                List.of(new Upload("script.pdf", "application/pdf", active.length, new ByteArrayInputStream(active))))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("JavaScript");

        Instant before = Instant.now().minusSeconds(5);
        DisputeDetail answered = asCreditor(creditorA, LEGAL, () -> portal.respond(dispute.id(), answer,
                List.of(upload("extrato.pdf"))));
        assertThat(answered.dispute().situation()).isEqualTo("ANSWERED");
        ResponseItem mine = answered.responses().getFirst();
        assertThat(mine.origin()).isEqualTo("CREDITOR_PORTAL");
        assertThat(mine.note()).isEqualTo("Pagamento localizado, segue o extrato");
        assertThat(mine.documentsInVerification()).isEqualTo(1);
        assertThat(mine.documents()).isEmpty();
        assertThat(mine.receivedAt()).isAfter(before);

        // a assessoria vê a resposta com a origem; o documento só vira evidência depois da verificação
        assertThat(asOperator(operatorX, () -> disputes.listEvidence(dispute.id())))
                .extracting(e -> e.fileName()).doesNotContain("extrato.pdf");
        var internal = asOperator(operatorX, () -> disputes.creditorResponses(dispute.id()));
        assertThat(internal).singleElement().satisfies(r -> {
            assertThat(r.source()).isEqualTo("CREDITOR_PORTAL");
            assertThat(r.recordedBy()).isEqualTo(creditorUser);
            assertThat(r.requestId()).isEqualTo(request.id());
        });
        drainJobs();
        assertThat(asOperator(operatorX, () -> disputes.listEvidence(dispute.id())))
                .extracting(e -> e.fileName()).contains("extrato.pdf");
        DisputeDetail afterScan = asCreditor(creditorA, READ_ONLY, () -> portal.dispute(dispute.id()));
        assertThat(afterScan.responses().getFirst().documents()).extracting(d -> d.fileName())
                .containsExactly("extrato.pdf");
        // contrato da ficha da contestação no portal (RS-26, SEG-F05)
        br.com.gestorcobranca.support.JsonContract.assertMatches(json.valueToTree(afterScan),
                "creditor-portal/dispute-detail.txt");

        // a resposta não resolve a contestação: segue aberta, sem "aguardando o credor"; o pedido ficou respondido
        DisputeView after = asOperator(operatorX, () -> disputes.get(dispute.id()));
        assertThat(after.status()).isIn("OPEN", "UNDER_REVIEW");
        assertThat(after.awaitingCreditorSince()).isNull();
        assertThat(asOperator(operatorX, () -> disputes.creditorRequests(dispute.id())).getFirst().status())
                .isEqualTo("ANSWERED");
        // responder de novo ao mesmo pedido → 409
        assertThatThrownBy(() -> asCreditor(creditorA, LEGAL, () -> portal.respond(dispute.id(), answer, List.of())))
                .isInstanceOf(ConflictException.class);
        assertThat(count("select count(*) from shared.business_event where event_type = 'dispute.creditor_responded' "
                + "and attributes ->> 'source' = 'CREDITOR_PORTAL'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'dispute.creditor_responded' and actor_type = 'CREDITOR'"))
                .isEqualTo(1);

        // resumo que a assessoria registra de outro canal é dela: o credor vê a posição, sem o texto
        asOperator(operatorX, () -> disputes.registerCreditorResponse(dispute.id(),
                new CreditorResponseCommand("INFORMATION", "Anotação interna do analista", null, null), List.of()));
        assertThat(asCreditor(creditorA, READ_ONLY, () -> portal.dispute(dispute.id())).responses())
                .filteredOn(r -> r.origin().equals("AGENCY")).singleElement()
                .satisfies(r -> assertThat(r.note()).isNull());
    }

    private DisputeView openDispute(UUID creditorId) {
        UUID debtor = debtor(randomCpf());
        load(creditorId, creditorId.equals(creditorA) ? portfolioA : portfolioB, debtor, "300.00");
        UUID caseId = activeCase(debtor, creditorId.equals(creditorA) ? portfolioA : portfolioB).id();
        return asOperator(operatorX, () -> disputes.open(caseId, new OpenDisputeCommand(Set.of(), "ALREADY_PAID",
                "DEBTOR", "Relato do devedor que o credor não vê", null, null)));
    }

    private static String item(UUID disputeId) {
        return disputeId.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private <T> T asCreditor(UUID creditorId, Set<Permission> permissions, Supplier<T> action) {
        return tenantExecution.callAs(Actor.creditor(tenant, creditorUser, session, creditorId, permissions), action);
    }

    private static InputStream pdf() {
        return new ByteArrayInputStream(br.com.gestorcobranca.support.TestPdfs.blank());
    }

    private static Upload upload(String name) {
        return new Upload(name, "application/pdf", br.com.gestorcobranca.support.TestPdfs.blank().length, pdf());
    }

    private static String randomCpf() {
        int[] d = new int[11];
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 9; i++) {
            d[i] = r.nextInt(10);
        }
        for (int j = 9; j < 11; j++) {
            int sum = 0;
            for (int i = 0; i < j; i++) {
                sum += d[i] * (j + 1 - i);
            }
            int v = 11 - sum % 11;
            d[j] = v >= 10 ? 0 : v;
        }
        StringBuilder s = new StringBuilder();
        for (int x : d) {
            s.append(x);
        }
        return s.toString();
    }
}
