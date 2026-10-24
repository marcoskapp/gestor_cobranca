package br.com.gestorcobranca.assistant;

import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.PolicyView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases.QualityQuery;
import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases.TurnDetail;
import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.PixInConversation;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationDetail;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CredentialsCommand;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.PixBrCode;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.security.CreditorScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pix na conversa contra PostgreSQL real, com o modelo simulado e o PSP simulado: conversa amigável com
 * acordo formalizado → Pix gerado, código entregue (BR Code dinâmico válido) e pago → conversão na
 * qualidade e atribuição intacta no incentive; reclamação na sessão → recusa com transferência; valor acima
 * do limite → recusa; modo com aprovação só para acordo formalizado por humano; modo sombra → só sugestão;
 * idempotência da entrega e reutilização da instrução aberta; escopo e RLS.
 */
class AssistantPixIT extends AssistantTestSupport {

    static final String WEBHOOK_SECRET = "segredo-hmac-de-teste-com-32-caracteres-ou-mais";

    @Autowired
    PixAccountUseCases pixAccounts;
    @Autowired
    PaymentSandboxUseCases pixSandbox;
    @Autowired
    JobQueue jobQueue;

    private void pixAccount() {
        asAdmin(() -> pixAccounts.create(new PixAccountUseCases.CreateAccountCommand(null, "PSP simulado", "SANDBOX",
                "pix@assessoria.test", "https://psp.sandbox.local/api/v2", "https://psp.sandbox.local/oauth/token",
                "HMAC", List.of(), new CredentialsCommand("client", "secret", WEBHOOK_SECRET, null, null))));
    }

    private PolicyView publishPixPolicy(String code, AssistantMode mode, UUID profile, BigDecimal maxAmount) {
        PolicySettings settings = new PolicySettings(mode, null, null, profile, null, null, null, null, null, null, null,
                null, List.of("negociação", "pagamento"), List.of("sorteio"), null, null, null, null, null, null, null,
                BigDecimal.ONE, null, new PixInConversation(true, maxAmount, null, null, null));
        return asAdmin(() -> assistantPolicies.publish(assistantPolicies.createDraft(new CreatePolicyCommand(creditorA,
                null, code, "Assistente " + code, settings)).id()));
    }

    /** Registro no PSP simulado, evento de ativação e entrega pelo assistente. */
    private void settlePix() {
        JobDrain.drain(applicationContext, jdbcTemplate, "payment.");
        awaitIdle("assistant");
        JobDrain.drain(applicationContext, jdbcTemplate, "assistant.");
        drainCommunication();
        awaitIdle("communication");
    }

    private Map<String, Object> lastPixRequest() {
        return system(() -> jdbcTemplate.queryForMap("select * from assistant.pix_request where conversation_id = ? "
                + "order by requested_at desc limit 1", conversationId));
    }

    private List<MessageView> pixMessages() {
        return assistantMessages().stream().filter(m -> m.content().contains("Pix Copia e Cola")).toList();
    }

    private void agreeInCash() {
        inbound("Oi, " + IDENTITY_ANSWER);
        inbound("quero pagar à vista");
        inbound("aceito O1");
    }

    @Test
    void friendlyConversationWithFormalizedAgreementIssuesDeliversAndConverts() {
        scenario("pix-ok", "800.00");
        pixAccount();
        PolicyView policy = publishPixPolicy("pix", AssistantMode.AUTONOMOUS, assistantProfile(true), null);
        handToAssistant();
        agreeInCash();
        UUID agreementId = UUID.fromString(lastTurnColumn("agreement_id::text"));
        awaitTrue("atribuição no incentive", () -> count("select count(*) from incentive.agreement_attribution "
                + "where agreement_id = ? and origin = 'ASSISTANT_AUTONOMOUS'", agreementId) == 1);

        inbound("pode mandar o pix");
        assertThat(outcomes()).last().isEqualTo("REPLIED");
        assertThat(lastAssistantText()).contains("foi gerado").contains("mensagem separada");
        Map<String, Object> request = lastPixRequest();
        assertThat(request.get("decision")).isEqualTo("ISSUED");
        assertThat(request.get("delivery_status")).isEqualTo("AWAITING");
        BigDecimal amount = (BigDecimal) request.get("amount");
        UUID instructionId = (UUID) request.get("instruction_id");
        // Pix imediato da parcela, exatamente o valor do acordo, pela conta Pix da assessoria
        assertThat(count("select count(*) from payment.payment_instruction where id = ? and type = 'PIX_IMMEDIATE' "
                + "and agreement_id = ? and amount = ?", instructionId, agreementId, amount)).isEqualTo(1);
        assertThat(count("select count(*) from negotiation.agreement where id = ? and total_amount = ?", agreementId,
                amount)).isEqualTo(1);

        settlePix();
        List<MessageView> delivered = pixMessages();
        assertThat(delivered).singleElement().satisfies(m -> {
            String code = m.content().lines().filter(l -> l.startsWith("000201")).findFirst().orElseThrow();
            PixBrCode parsed = PixBrCode.parse(code).orElseThrow();
            assertThat(parsed.isDynamic()).isTrue();
            assertThat(parsed.staticKey()).isEmpty();
            assertThat(m.content()).contains("o recebedor é GESTOR SANDBOX").contains("válido até")
                    .contains("pessoa física");
        });
        assertThat(lastPixRequest().get("delivery_status")).isEqualTo("DELIVERED");

        // trilha: critérios avaliados no turno, código nunca vai ao modelo
        UUID pixTurn = (UUID) request.get("turn_id");
        TurnDetail detail = asSupervisor(() -> trail.turn(pixTurn));
        assertThat(detail.toolCalls()).extracting(c -> c.tool()).contains("get_installments", "generate_payment");
        assertThat(detail.pixRequests()).singleElement().satisfies(p -> {
            assertThat(p.decision()).isEqualTo("ISSUED");
            assertThat(p.criteria()).hasSize(12).allMatch(c -> c.passed());
        });
        assertThat(detail.context() + detail.outputText()).doesNotContain("br.gov.bcb.pix");
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.pix_issued' "
                + "and reference_id = ?", request.get("id"))).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.pix_delivery' "
                + "and attributes ->> 'outcome' = 'DELIVERED'")).isEqualTo(1);

        // idempotência da entrega: reagendar não reenvia
        system(() -> jobQueue.enqueue("assistant.pix_delivery", Map.of("pixRequestId", request.get("id").toString()),
                null, null));
        JobDrain.drain(applicationContext, jdbcTemplate, "assistant.");
        drainCommunication();
        assertThat(pixMessages()).hasSize(1);

        // pedido de novo no mesmo dia: reutiliza a instrução aberta igual (nada duplicado no payment)
        inbound("manda o pix de novo por favor");
        assertThat(lastPixRequest().get("instruction_id")).isEqualTo(instructionId);
        assertThat(lastPixRequest().get("reused_instruction")).isEqualTo(true);
        assertThat(count("select count(*) from payment.payment_instruction where agreement_id = ?", agreementId))
                .isEqualTo(1);
        settlePix();
        assertThat(pixMessages()).hasSize(2);

        // pagamento pelo PSP simulado → instrução paga → conversão Pix emitido → pago
        asAdmin(() -> pixSandbox.simulatePixPayment(instructionId, null, true));
        JobDrain.drain(applicationContext, jdbcTemplate, "payment.");
        awaitIdle("assistant");
        awaitTrue("Pix do assistente pago", () -> count("select count(*) from assistant.pix_request "
                + "where instruction_id = ? and paid_at is not null", instructionId) == 2);
        var quality = asSupervisor(() -> reviews.quality(new QualityQuery(null, null, creditorA, null)));
        assertThat(quality.pix().issued()).isEqualTo(2);
        assertThat(quality.pix().delivered()).isEqualTo(2);
        assertThat(quality.pix().paidWithin24h()).isEqualTo(2);
        assertThat(quality.pix().conversionRate()).isEqualByComparingTo("1");
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.pix_paid'"))
                .isEqualTo(2);
        // atribuição continua a do acordo (o Pix não muda quem fechou)
        JobDrain.drain(applicationContext, jdbcTemplate, "negotiation.");
        assertThat(count("select count(*) from incentive.agreement_attribution where agreement_id = ?", agreementId))
                .isEqualTo(1);
        assertThat(count("select count(*) from incentive.agreement_attribution where agreement_id = ? "
                + "and origin = 'ASSISTANT_AUTONOMOUS'", agreementId)).isEqualTo(1);
        assertThat(count("select count(*) from assistant.agreement_fact where agreement_id = ? "
                + "and assistant_policy_id = ?", agreementId, policy.id())).isEqualTo(1);

        // escopo: supervisor só do credor B não vê o turno (nem os critérios)
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.SUPERVISOR, CreditorScope.of(Set.of(creditorB)),
                () -> trail.turn(pixTurn))).isInstanceOf(ResourceNotFoundException.class);
        // RLS: outra assessoria não vê os pedidos de Pix; a avaliação é imutável
        long visible = tenantExecution.callInTenant(Ids.newId(), "test.rls", () -> jdbcTemplate.queryForObject(
                "select count(*) from assistant.pix_request", Long.class));
        assertThat(visible).isZero();
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update assistant.pix_request set criteria = '[]'::jsonb where id = ?", request.get("id"))))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from assistant.pix_request where id = ?",
                request.get("id")))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void complaintInTheSessionDeniesThePixAndHandsOff() {
        scenario("pix-recl", "800.00");
        pixAccount();
        publishPixPolicy("pix", AssistantMode.AUTONOMOUS, assistantProfile(true), null);
        handToAssistant();
        agreeInCash();
        inbound("isso é um absurdo, vou fazer uma reclamação no Reclame Aqui");
        assertThat(lastTurnColumn("handoff_reason")).isEqualTo("COMPLAINT");
        // o atendente devolve a conversa ao assistente; a reclamação continua na sessão
        asOperator(operatorX, () -> conversations.returnToAssistant(conversationId, "Cliente acalmou"));
        inbound("pode mandar o pix");

        Map<String, Object> request = lastPixRequest();
        assertThat(request.get("decision")).isEqualTo("DENIED");
        assertThat(request.get("denial_code")).isEqualTo("CONVERSATION_RISK");
        assertThat(request.get("instruction_id")).isNull();
        assertThat(String.valueOf(request.get("criteria"))).contains("COMPLAINT");
        assertThat(outcomes()).last().isEqualTo("HANDED_OFF");
        assertThat(lastTurnColumn("handoff_reason")).isEqualTo("PAYMENT_ASSISTANCE");
        awaitIdle("communication");
        ConversationDetail detail = system(() -> conversations.get(conversationId));
        assertThat(detail.conversation().ownerType()).isEqualTo("QUEUE");
        assertThat(lastAssistantText()).contains("transferir seu atendimento");
        assertThat(count("select count(*) from payment.payment_instruction where debtor_id = ?", debtor)).isZero();
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.pix_denied' "
                + "and attributes ->> 'reason' = 'CONVERSATION_RISK'")).isEqualTo(1);
    }

    @Test
    void amountAboveThePolicyLimitIsDenied() {
        scenario("pix-lim", "800.00");
        pixAccount();
        publishPixPolicy("pix", AssistantMode.AUTONOMOUS, assistantProfile(true), new BigDecimal("300.00"));
        handToAssistant();
        agreeInCash();
        inbound("quero pagar agora no pix");
        assertThat(lastPixRequest().get("denial_code")).isEqualTo("AMOUNT_ABOVE_LIMIT");
        assertThat(lastTurnColumn("handoff_reason")).isEqualTo("PAYMENT_ASSISTANCE");
        assertThat(count("select count(*) from payment.payment_instruction where debtor_id = ?", debtor)).isZero();
        var quality = asSupervisor(() -> reviews.quality(new QualityQuery(null, null, creditorA, null)));
        assertThat(quality.pix().deniedByReason()).containsEntry("AMOUNT_ABOVE_LIMIT", 1L);
    }

    @Test
    void approvalModeIssuesOnlyForAgreementFormalizedByAHuman() {
        scenario("pix-appr", "800.00");
        pixAccount();
        publishPixPolicy("pix", AssistantMode.AUTONOMOUS_WITH_APPROVAL, null, null);
        handToAssistant();
        agreeInCash();
        assertThat(outcomes()).last().isEqualTo("APPROVAL_REQUESTED");
        UUID proposalId = UUID.fromString(lastTurnColumn("proposal_id::text"));
        UUID agreementId = asOperator(operatorX, () -> proposals.formalize(proposalId)).id();
        asOperator(operatorX, () -> conversations.returnToAssistant(conversationId, "Acordo formalizado"));

        inbound("quero pagar agora no pix");
        Map<String, Object> request = lastPixRequest();
        assertThat(request.get("decision")).isEqualTo("ISSUED");
        assertThat(request.get("agreement_id")).isEqualTo(agreementId);
        settlePix();
        assertThat(pixMessages()).hasSize(1);
        awaitTrue("atribuição confirmada pelo humano", () -> count("select count(*) from incentive.agreement_attribution "
                + "where agreement_id = ? and origin = 'ASSISTANT_HANDOFF_CONFIRMED'", agreementId) == 1);
    }

    @Test
    void shadowModeOnlySuggestsToTheOperator() {
        scenario("pix-shadow", "800.00");
        pixAccount();
        publishPixPolicy("pix", AssistantMode.AUTONOMOUS, assistantProfile(true), null);
        handToAssistant();
        agreeInCash();
        UUID agreementId = UUID.fromString(lastTurnColumn("agreement_id::text"));
        // nova versão da política em modo sombra: a próxima mensagem vai para a fila e o assistente só rascunha
        publishPixPolicy("pix", AssistantMode.SHADOW, null, null);
        inbound("oi");
        assertThat(conversation().ownerType()).isEqualTo("QUEUE");

        inbound("pode mandar o pix");
        assertThat(outcomes()).last().isEqualTo("DRAFTED");
        assertThat(lastPixRequest().get("decision")).isEqualTo("SUGGESTED");
        ConversationDetail detail = system(() -> conversations.get(conversationId));
        assertThat(detail.pendingDrafts()).last().satisfies(d -> assertThat(d.rationale()).contains("Pix elegível"));
        assertThat(count("select count(*) from payment.payment_instruction where agreement_id = ?", agreementId)).isZero();
        assertThat(pixMessages()).isEmpty();
    }
}
