package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.collection.application.port.in.DisputeIntakeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeIntakeUseCases.PortalDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeIntakeUseCases.PortalDisputeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I14: a contestação que o devedor registra no portal vira contestação no collection (fluxo entre módulos pela
 * interface pública {@code DisputeIntakeUseCases}, job {@code portal.dispute_intake}), com o prazo do CDC contando do
 * pedido, origem DEBTOR, cobrança suspensa e entrada idempotente.
 */
class PortalDisputeIntakeIT extends PortalTestSupport {

    @Autowired
    DisputeIntakeUseCases intake;
    @Autowired
    br.com.gestorcobranca.collection.application.port.in.DisputeUseCases disputes;

    @BeforeEach
    void setUp() {
        provision("pdisp");
    }

    @Test
    void portalDisputeOpensTheCollectionDisputeFromTheRequestInstantOnce() {
        String token = loginReady(CPF_MARIA);
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "DISPUTE");
        body.put("preferredChannel", "WHATSAPP");
        body.put("portfolioId", portfolioA.toString());
        body.put("disputeType", "ALREADY_PAID");
        body.put("note", "Paguei a parcela de agosto no banco");
        Response created = post("/api/portal/v1/me/service-requests", body, token);
        assertThat(created.status()).isEqualTo(201);
        UUID requestId = UUID.fromString(created.json().get("id").asString());
        assertThat(created.json().get("disputeIntakeStatus").asString()).isEqualTo("PENDING");

        drainJobs("portal.");

        String reference = "portal_request:" + requestId;
        assertThat(count("select count(*) from collection.dispute where origin_reference = ?", reference)).isEqualTo(1);
        UUID caseId = system(() -> jdbcTemplate.queryForObject(
                "select id from collection.collection_case where debtor_id = ? and portfolio_id = ? and closed_at is null",
                UUID.class, maria, portfolioA));
        Map<String, Object> dispute = system(() -> jdbcTemplate.queryForMap(
                "select id, case_id, origin, type, status, blocks_collection, opened_at, response_due_date "
                        + "from collection.dispute where origin_reference = ?", reference));
        assertThat(dispute.get("case_id")).isEqualTo(caseId);
        assertThat(dispute.get("origin")).isEqualTo("DEBTOR");
        assertThat(dispute.get("type")).isEqualTo("ALREADY_PAID");
        assertThat(dispute.get("status")).isEqualTo("OPEN");
        assertThat(dispute.get("blocks_collection")).isEqualTo(true);
        // o prazo do CDC conta do pedido no portal (não da execução do job)
        Instant requestedAt = system(() -> jdbcTemplate.queryForObject(
                "select created_at from portal.service_request where id = ?", Timestamp.class, requestId)).toInstant();
        assertThat(((Timestamp) dispute.get("opened_at")).toInstant()).isEqualTo(requestedAt);
        assertThat(((java.sql.Date) dispute.get("response_due_date")).toLocalDate()).isAfter(today);
        // o relato do devedor não é copiado para a contestação (fica no pedido do portal)
        assertThat(system(() -> disputes.get((UUID) dispute.get("id")).description()))
                .contains("protocolo").contains("Pedidos de atendimento").doesNotContain("agosto");
        // caso em contestação, resultado no pedido do portal
        assertThat(count("select count(*) from collection.collection_case where id = ? and status = 'DISPUTED'", caseId))
                .isEqualTo(1);
        assertThat(count("select count(*) from portal.service_request_dispute where request_id = ? "
                + "and status = 'OPENED' and disputes_opened = 1", requestId)).isEqualTo(1);
        assertThat(get("/api/portal/v1/me/service-requests", token).json().get(0).get("disputeIntakeStatus").asString())
                .isEqualTo("OPENED");
        // auditoria e evento de negócio
        assertThat(count("select count(*) from shared.audit_event where action = 'portal.dispute_registered' "
                + "and entity_id = ?", requestId)).isEqualTo(1);

        // idempotente: repetir a entrada do mesmo pedido não abre outra contestação
        PortalDisputeResult again = system(() -> intake.openFromPortal(new PortalDisputeCommand(requestId, "X",
                maria, creditorA, Set.of(portfolioA), "ALREADY_PAID", true, requestedAt)));
        assertThat(again.outcome()).isEqualTo("OPENED");
        assertThat(again.disputeIds()).hasSize(1);
        assertThat(count("select count(*) from collection.dispute where case_id = ?", caseId)).isEqualTo(1);

        // novo pedido do mesmo devedor sem carteira: o caso já tem contestação em aberto → não duplica
        Map<String, Object> second = new HashMap<>(body);
        second.remove("portfolioId");
        Response other = post("/api/portal/v1/me/service-requests", second, token);
        UUID otherId = UUID.fromString(other.json().get("id").asString());
        drainJobs("portal.");
        assertThat(count("select count(*) from portal.service_request_dispute where request_id = ? "
                + "and status = 'ALREADY_OPEN'", otherId)).isEqualTo(1);
        assertThat(count("select count(*) from collection.dispute where case_id = ?", caseId)).isEqualTo(1);
    }

    @Test
    void intakeOnlyBySystemAndWithoutCaseRecordsNoCase() {
        PortalDisputeResult none = system(() -> intake.openFromPortal(new PortalDisputeCommand(UUID.randomUUID(), "P",
                joao, creditorB, Set.of(portfolioA), null, false, Instant.now())));
        assertThat(none.outcome()).isEqualTo("NO_CASE");
        assertThatThrownBy(() -> asOperator(() -> intake.openFromPortal(new PortalDisputeCommand(UUID.randomUUID(),
                "P", joao, creditorA, Set.of(portfolioA), null, false, Instant.now()))))
                .hasMessageContaining("portal");
    }
}
