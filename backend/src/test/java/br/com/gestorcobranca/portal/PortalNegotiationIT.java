package br.com.gestorcobranca.portal;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.JustificationInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases.SimulatedNotification;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Autonegociação ponta a ponta: aviso de privacidade (registrado no compliance), consulta, ofertas no
 * perfil PORTAL, recusa no servidor de condição fora do perfil, aceite com termo e evidência, formalização,
 * Pix da entrada, pagamento confirmado (funil), atribuição PORTAL_SELF_SERVICE, escopo estrito do devedor
 * e pedido de atendimento humano.
 */
class PortalNegotiationIT extends PortalTestSupport {

    @Autowired
    NegotiationSimulationUseCases simulations;
    @Autowired
    ProposalUseCases proposals;
    @Autowired
    AgreementUseCases agreements;
    @Autowired
    PaymentInstructionUseCases instructions;
    @Autowired
    PaymentSandboxUseCases paymentSandbox;

    @BeforeEach
    void setUp() {
        provision("pneg");
    }

    @Test
    void privacyNoticeMustBeAcknowledgedAndIsRecordedInCompliance() {
        String token = login(CPF_MARIA);
        Response blocked = get("/api/portal/v1/me/debts", token);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.json().get("code").asString()).isEqualTo("portal.privacy_ack_required");
        assertThat(post("/api/portal/v1/me/session/privacy", Map.of("noticeVersion", 2), token).status()).isEqualTo(409);
        Response ack = post("/api/portal/v1/me/session/privacy", Map.of("noticeVersion", 1), token);
        assertThat(ack.status()).isEqualTo(200);
        assertThat(ack.json().get("privacyAcknowledged").asBoolean()).isTrue();
        awaitTrue("reconhecimento registrado no compliance", () -> count(
                "select count(*) from compliance.privacy_notice_acknowledgment where debtor_id = ? and creditor_id = ? "
                        + "and channel = 'PORTAL' and notice_version = 1", maria, creditorA) == 1);
        assertThat(get("/api/portal/v1/me/debts", token).status()).isEqualTo(200);
        // novo texto do aviso → nova versão → novo reconhecimento exigido
        var c = configuration(slug, "NONE");
        asAdmin(() -> portalConfigurations.update(portal.id(), new br.com.gestorcobranca.portal.application.port.in
                .PortalConfigurationUseCases.ConfigurationCommand(c.creditorId(), c.slug(), true, c.displayName(),
                c.primaryColor(), c.accentColor(), c.portfolioIds(), c.otpChannels(), c.knowledgeChallenge(),
                c.linkValidityHours(), c.privacyNoticeText() + " Atualizado.", c.supportText())));
        assertThat(get("/api/portal/v1/me/debts", token).status()).isEqualTo(409);
    }

    @Test
    void selfServiceAgreementWithPixPaymentAndAttribution() {
        String token = loginReady(CPF_MARIA);
        Response debts = get("/api/portal/v1/me/debts", token);
        assertThat(debts.status()).isEqualTo(200);
        JsonNode portfolios = debts.json().get("portfolios");
        // só a carteira habilitada aparece (o título da A-02 fica de fora)
        assertThat(portfolios.size()).isEqualTo(1);
        JsonNode debt = portfolios.get(0);
        assertThat(UUID.fromString(debt.get("portfolioId").asString())).isEqualTo(portfolioA);
        assertThat(debt.get("titles").size()).isEqualTo(2);
        assertThat(debt.get("negotiable").asBoolean()).isTrue();
        BigDecimal updated = new BigDecimal(debt.get("updatedAmount").asString());
        assertThat(updated).isGreaterThan(new BigDecimal("1500.00"));
        assertThat(debt.get("titles").get(0).get("reference").asString()).startsWith("final ");

        Response offers = get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token);
        assertThat(offers.status()).isEqualTo(200);
        JsonNode list = offers.json().get("offers");
        assertThat(list.size()).isBetween(2, 6);
        assertThat(offers.json().get("maxInstallments").asInt()).isEqualTo(6);
        JsonNode cash = list.get(0);
        assertThat(cash.get("installments").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(cash.get("discount").asString())).isPositive();
        UUID simulationId = UUID.fromString(offers.json().get("simulationId").asString());
        assertThat(count("select count(*) from negotiation.simulation where id = ? and actor_type = 'DEBTOR' "
                + "and justification_reason = 'SELF_SERVICE_PREAUTHORIZED' and authority_profile_code = 'PORTAL'", simulationId))
                .isEqualTo(1);

        // condição fora do que foi oferecido: recusada no servidor
        Map<String, Object> tampered = accept(simulationId, cash.get("level").asInt(), 1);
        tampered.put("level", 3);
        Response refused = post("/api/portal/v1/me/agreements", tampered, token);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.json().get("code").asString()).isEqualTo("portal.offer_unavailable");
        Map<String, Object> noTerms = accept(simulationId, cash.get("level").asInt(), 1);
        noTerms.put("termsAccepted", false);
        assertThat(post("/api/portal/v1/me/agreements", noTerms, token).json().get("code").asString())
                .isEqualTo("portal.terms_not_accepted");

        // aceite à vista: proposta + aceite com termo (canal PORTAL, IP, agente, hash) + formalização + Pix
        Response accepted = post("/api/portal/v1/me/agreements", accept(simulationId, cash.get("level").asInt(), 1), token);
        assertThat(accepted.status()).isEqualTo(201);
        JsonNode agreement = accepted.json().get("agreement");
        UUID agreementId = UUID.fromString(agreement.get("id").asString());
        assertThat(agreement.get("status").asString()).isEqualTo("FORMALIZED");
        assertThat(agreement.get("termSha256").asString()).hasSize(64);
        assertThat(new BigDecimal(agreement.get("totalAmount").asString()))
                .isEqualByComparingTo(new BigDecimal(cash.get("total").asString()));
        JsonNode pix = accepted.json().get("pix");
        assertThat(pix.get("status").asString()).isEqualTo("PENDING");
        assertThat(count("select count(*) from negotiation.proposal where agreement_id = ? and actor_type = 'DEBTOR' "
                + "and acceptance_channel = 'PORTAL' and accepted_by_type = 'DEBTOR' and acceptance_ip is not null "
                + "and acceptance_user_agent = 'PortalIT/1.0' and acceptance_reference like 'portal-session:%'", agreementId))
                .isEqualTo(1);
        assertThat(count("select count(*) from negotiation.agreement where id = ? and origin = 'PORTAL_SELF_SERVICE' "
                + "and closer_type = 'DEBTOR'", agreementId)).isEqualTo(1);
        awaitTrue("atribuição PORTAL_SELF_SERVICE no incentive", () -> count(
                "select count(*) from incentive.agreement_attribution where agreement_id = ? and origin = 'PORTAL_SELF_SERVICE'",
                agreementId) == 1);
        // não há segundo acordo para a mesma dívida
        assertThat(get("/api/portal/v1/me/portfolios/" + portfolioA + "/offers", token).json().get("code").asString())
                .isEqualTo("portal.agreement_active");

        // Pix registrado no PSP (sandbox) → copia e cola disponível; 2ª via devolve o mesmo
        drainJobs("payment.");
        UUID installmentId = UUID.fromString(agreement.get("installments").get(0).get("id").asString());
        Response second = post("/api/portal/v1/me/agreements/" + agreementId + "/installments/" + installmentId + "/pix",
                Map.of(), token);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(second.json().get("copyPaste").asString()).startsWith("000201");
        assertThat(second.json().get("instructionId").asString()).isEqualTo(pix.get("instructionId").asString());

        // termo PDF do negotiation (download registrado)
        Response term = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/portal/v1/me/agreements/"
                + agreementId + "/term")).GET(), token);
        assertThat(term.status()).isEqualTo(200);
        assertThat(term.body()).startsWith("%PDF");
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED' and actor_id = ?",
                maria)).isEqualTo(1);

        // pagamento pelo PSP → acordo em pagamento → etapa final do funil
        UUID instructionId = UUID.fromString(second.json().get("instructionId").asString());
        SimulatedNotification paid = asAdmin(() -> paymentSandbox.simulatePixPayment(instructionId, null, true));
        assertThat(paid.endToEndId()).isNotBlank();
        drainJobs("payment.");
        awaitTrue("primeiro pagamento registrado no portal", () -> count(
                "select count(*) from portal.portal_agreement where agreement_id = ? and first_paid_at is not null",
                agreementId) == 1);
        var funnel = asAdmin(() -> portalConfigurations.funnel(portal.id(), null, null));
        assertThat(funnel.accesses()).isEqualTo(1);
        assertThat(funnel.simulations()).isEqualTo(1);
        assertThat(funnel.acceptances()).isEqualTo(1);
        assertThat(funnel.pixIssued()).isEqualTo(1);
        assertThat(funnel.paid()).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'portal.agreement_formalized' "
                + "and debtor_id = ?", maria)).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'portal.payment_confirmed' "
                + "and debtor_id = ?", maria)).isEqualTo(1);

        Response mine = get("/api/portal/v1/me/agreements", token);
        assertThat(mine.json().get(0).get("closedInPortal").asBoolean()).isTrue();
    }

    @Test
    void installmentPlanWithinPortalProfileAndOutsideProfileRefusedByTheGuard() {
        String token = loginReady(CPF_MARIA);
        Response sim = post("/api/portal/v1/me/portfolios/" + portfolioA + "/simulations", Map.of("installments", 3), token);
        assertThat(sim.status()).isEqualTo(200);
        JsonNode offer = sim.json().get("offers").get(0);
        assertThat(offer.get("installments").asInt()).isEqualTo(3);
        assertThat(offer.get("schedule").size()).isEqualTo(3);
        // acima do perfil PORTAL (6 pagamentos): recusado antes de simular
        Response tooMany = post("/api/portal/v1/me/portfolios/" + portfolioA + "/simulations", Map.of("installments", 8),
                token);
        assertThat(tooMany.status()).isEqualTo(422);
        assertThat(tooMany.json().get("code").asString()).isEqualTo("portal.installments_unavailable");

        // defesa em profundidade: o devedor chamando o negotiation diretamente com condição acima do perfil
        SimulationView direct = asDebtor(maria, () -> simulations.simulate(new SimulateCommand(creditorA, maria, List.of(),
                null, 1, new JustificationInput("SELF_SERVICE_PREAUTHORIZED", null), List.of(new PlanInput(10, null, null)),
                "PORTAL")));
        assertThatThrownBy(() -> asDebtor(maria, () -> proposals.create(new CreateProposalCommand(direct.id(), 1, null,
                new PlanInput(10, null, null), null, null, false, "PORTAL"))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isIn("proposal.outside_self_service",
                        "proposal.conditions_rejected"));
        // o devedor não usa motivo de concessão humano, nem humano usa o do portal
        assertThatThrownBy(() -> asDebtor(maria, () -> simulations.simulate(new SimulateCommand(creditorA, maria, List.of(),
                null, 1, new JustificationInput("PAYMENT_CAPACITY", null), List.of(), "PORTAL"))))
                .hasMessageContaining("pré-autorizada");
        assertThatThrownBy(() -> asOperator(() -> simulations.simulate(new SimulateCommand(creditorA, maria, List.of(),
                null, 1, new JustificationInput("SELF_SERVICE_PREAUTHORIZED", null), List.of(), "PHONE"))))
                .hasMessageContaining("autonegociação");
    }

    @Test
    void debtorScopeIsStrictlyTheirOwn() {
        // acordo do João (fechado por um operador)
        UUID simJoao = asOperator(() -> simulations.simulate(new SimulateCommand(creditorA, joao, List.of(), null, null, null,
                List.of(new PlanInput(1, null, null)), "PHONE")).id());
        UUID proposalJoao = asOperator(() -> proposals.create(new CreateProposalCommand(simJoao, 0, null,
                new PlanInput(1, null, null), null, null, false, null)).id());
        asOperator(() -> proposals.accept(proposalJoao, new ProposalUseCases.AcceptCommand("PHONE", "call-1", null, null)));
        UUID agreementJoao = asOperator(() -> proposals.formalize(proposalJoao)).id();

        String token = loginReady(CPF_MARIA);
        // pelo portal: o acordo, o termo e o Pix de outro devedor "não existem"
        assertThat(get("/api/portal/v1/me/agreements/" + agreementJoao, token).status()).isEqualTo(404);
        assertThat(get("/api/portal/v1/me/agreements/" + agreementJoao + "/term", token).status()).isEqualTo(404);
        assertThat(get("/api/portal/v1/me/agreements", token).json().size()).isZero();
        // carteira não habilitada e simulação de outra pessoa
        assertThat(get("/api/portal/v1/me/portfolios/" + portfolioA2 + "/offers", token).status()).isEqualTo(404);
        assertThat(post("/api/portal/v1/me/agreements", accept(simJoao, 0, 1), token).status()).isEqualTo(404);

        // nos módulos (defesa em profundidade, mesmo com o credor no escopo): 404 + SCOPE_DENIED
        assertThatThrownBy(() -> asDebtor(maria, () -> agreements.get(agreementJoao)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asDebtor(maria, () -> simulations.simulate(new SimulateCommand(creditorA, joao, List.of(),
                null, null, null, List.of(), "PORTAL")))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asDebtor(maria, () -> simulations.get(simJoao))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asDebtor(maria, () -> agreements.search(new AgreementFilter(creditorA, joao, null, null),
                PageQuery.of(0, 10))).content()).isEmpty();
        assertThat(asDebtor(maria, () -> instructions.search(new InstructionFilter(creditorA, joao, null, null, null),
                PageQuery.of(0, 10))).content()).allSatisfy(i -> assertThat(i.debtorId()).isEqualTo(maria));
        UUID installment = asOperator(() -> agreements.get(agreementJoao)).installments().getFirst().id();
        assertThatThrownBy(() -> asDebtor(maria, () -> instructions.create(new PaymentInstructionUseCases
                .CreateInstructionCommand("PIX_IMMEDIATE", agreementJoao, installment, null, null, null, null, "PORTAL"),
                null))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' and actor_id = ?", maria))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void requestsOutsideThePortalGoToTheHumanQueue() {
        String token = loginReady(CPF_MARIA);
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "DISPUTE");
        body.put("preferredChannel", "WHATSAPP");
        body.put("portfolioId", portfolioA.toString());
        body.put("note", "Já paguei a parcela de agosto");
        Response created = post("/api/portal/v1/me/service-requests", body, token);
        assertThat(created.status()).isEqualTo(201);
        String protocol = created.json().get("protocol").asString();
        assertThat(protocol).hasSize(10);
        UUID requestId = UUID.fromString(created.json().get("id").asString());
        drainJobs("portal.");
        assertThat(count("select count(*) from portal.service_request where id = ? and status = 'FORWARDED' "
                + "and conversation_id is not null", requestId)).isEqualTo(1);
        // a observação livre fica criptografada
        assertThat(count("select count(*) from portal.service_request where note_encrypted like '%agosto%'")).isZero();
        UUID conversation = system(() -> jdbcTemplate.queryForObject(
                "select conversation_id from portal.service_request where id = ?", UUID.class, requestId));
        awaitTrue("conversa transferida para a fila", () -> count(
                "select count(*) from communication.conversation where id = ? and owner_type = 'QUEUE'", conversation) == 1);
        assertThat(count("select count(*) from communication.conversation_handoff where conversation_id = ? "
                + "and reason_code = 'DISPUTE'", conversation)).isEqualTo(1);
        // lista interna dos pedidos (INBOX_VIEW), com a mensagem do devedor
        var listed = asOperator(() -> portalOperations.serviceRequests(creditorA, null, PageQuery.of(0, 10)));
        assertThat(listed.content()).singleElement().satisfies(r -> {
            assertThat(r.protocol()).isEqualTo(protocol);
            assertThat(r.note()).contains("agosto");
        });
        assertThat(get("/api/portal/v1/me/service-requests", token).json().get(0).get("status").asString())
                .isEqualTo("FORWARDED");
    }

    private static Map<String, Object> accept(UUID simulationId, int level, int installments) {
        Map<String, Object> m = new HashMap<>();
        m.put("simulationId", simulationId.toString());
        m.put("level", level);
        m.put("installments", installments);
        m.put("termsAccepted", true);
        return m;
    }
}
