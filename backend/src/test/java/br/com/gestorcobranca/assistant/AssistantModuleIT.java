package br.com.gestorcobranca.assistant;

import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.PolicyView;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases.QualityQuery;
import br.com.gestorcobranca.assistant.application.port.in.AssistantReviewUseCases.ReviewCommand;
import br.com.gestorcobranca.assistant.application.port.in.AssistantTrailUseCases.TurnDetail;
import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.ModelRouting;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationDetail;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxos do assistente contra PostgreSQL real com o modelo simulado: conversa → identidade → simulação →
 * proposta dentro da alçada → acordo autônomo (atribuição {@code ASSISTANT_AUTONOMOUS} no incentive);
 * acima da alçada → handoff; sombra gera rascunho; gatilho de vulnerabilidade transfere; porteiro nega
 * o envio; guardrail; escopo por credor; RLS e trilha append-only.
 */
class AssistantModuleIT extends AssistantTestSupport {

    @Test
    void autonomousAgreementWithinAuthorityIsAttributedToTheAssistant() {
        scenario("ast-auto", "800.00");
        PolicyView policy = publishPolicy("autonomo", creditorA, AssistantMode.AUTONOMOUS, assistantProfile(true));
        handToAssistant();

        inbound("Oi, " + IDENTITY_ANSWER);
        assertThat(lastAssistantText()).contains("assistente virtual").contains("confirmados");
        assertThat(system(() -> jdbcTemplate.queryForObject("select identity_status from assistant.assistant_session "
                + "where conversation_id = ?", String.class, conversationId))).isEqualTo("VERIFIED");

        inbound("quero pagar à vista");
        assertThat(lastAssistantText()).contains("O1:").contains("à vista, R$ ");

        inbound("aceito O1");
        assertThat(outcomes()).containsExactly("REPLIED", "REPLIED", "REPLIED");
        assertThat(lastAssistantText()).contains("Acordo formalizado");
        UUID agreementId = UUID.fromString(lastTurnColumn("agreement_id::text"));
        assertThat(count("select count(*) from negotiation.agreement where id = ? and closer_type = 'ASSISTANT'",
                agreementId)).isEqualTo(1);
        // atribuição do acordo no incentive: fechado pelo assistente sozinho (0% individual, pool da equipe)
        awaitTrue("atribuição no incentive", () -> count("select count(*) from incentive.agreement_attribution "
                + "where agreement_id = ? and origin = 'ASSISTANT_AUTONOMOUS'", agreementId) == 1);
        awaitTrue("fato de acordo no assistant", () -> count("select count(*) from assistant.agreement_fact "
                + "where agreement_id = ? and origin = 'ASSISTANT_AUTONOMOUS' and assistant_policy_id = ?",
                agreementId, policy.id()) == 1);
        assertThat(conversation().ownerType()).isEqualTo("ASSISTANT");

        // trilha: contexto com hash, versão de política e prompt, ferramentas validadas, sem documento no contexto
        TurnDetail detail = asSupervisor(() -> trail.turn(UUID.fromString(lastTurnColumn("id::text"))));
        assertThat(detail.turn().policyVersion()).isEqualTo(1);
        assertThat(detail.turn().promptCode()).isEqualTo("negociacao");
        assertThat(detail.turn().contextSha256()).hasSize(64);
        assertThat(detail.toolCalls()).extracting(c -> c.tool())
                .containsExactly("register_intent", "propose_offer", "register_acceptance");
        assertThat(detail.context()).doesNotContain("529.982").doesNotContain(debtor.toString()).doesNotContain("1985");
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.replied' "
                + "and attributes ->> 'conversationId' = ?", conversationId.toString())).isEqualTo(3);
        // nível de modelo por etapa (política BY_STAGE): identificação no padrão; a negociação passa ao avançado
        // assim que o modelo registra a intenção (a 1ª chamada do turno ainda é padrão); com ofertas na etapa, o
        // aceite inteiro vai ao avançado
        List<String> tiers = modelTiers("assistant.replied");
        assertThat(tiers).hasSize(3);
        assertThat(tiers.get(0)).matches("STANDARD/0/\\d+");
        assertThat(tiers.get(1)).satisfies(t -> {
            String[] p = t.split("/");
            assertThat(p[0]).isEqualTo("ADVANCED");
            assertThat(Integer.parseInt(p[1])).isEqualTo(Integer.parseInt(p[2]) - 1);
        });
        assertThat(tiers.get(2)).satisfies(t -> {
            String[] p = t.split("/");
            assertThat(p[0]).isEqualTo("ADVANCED");
            assertThat(p[1]).isEqualTo(p[2]);
        });
        assertThat(count("select count(*) from shared.access_log where event_type = 'SENSITIVE_DATA_VIEWED' "
                + "and details ->> 'resource' = 'assistant_turn'")).isEqualTo(1);
    }

    @Test
    void aboveAuthorityHandsOffWithTheProposalAsSuggestion() {
        scenario("ast-hand", "5000.00");
        publishPolicy("inicial", creditorA, AssistantMode.AUTONOMOUS_WITH_APPROVAL, null);
        handToAssistant();
        inbound(IDENTITY_ANSWER);
        inbound("quero pagar à vista");
        inbound("aceito O1");

        assertThat(outcomes()).last().isEqualTo("HANDED_OFF");
        assertThat(lastTurnColumn("handoff_reason")).isEqualTo("REQUIRES_APPROVAL");
        UUID proposalId = UUID.fromString(lastTurnColumn("proposal_id::text"));
        assertThat(count("select count(*) from negotiation.proposal where id = ? and status = 'HANDED_OFF'", proposalId))
                .isEqualTo(1);
        awaitIdle("communication");
        ConversationDetail detail = system(() -> conversations.get(conversationId));
        assertThat(detail.conversation().ownerType()).isEqualTo("QUEUE");
        assertThat(detail.handoffs()).anyMatch(h -> "REQUIRES_APPROVAL".equals(h.reasonCode())
                && proposalId.equals(h.referenceId()));
        assertThat(lastAssistantText()).contains("transferir seu atendimento");
        // nova mensagem com a conversa na fila: o assistente não responde
        int before = assistantMessages().size();
        inbound("e agora?");
        assertThat(assistantMessages()).hasSize(before);
    }

    @Test
    void approvalModeRegistersAcceptanceAndHandsFormalizationToHumans() {
        scenario("ast-appr", "800.00");
        publishPolicy("aprovacao", creditorA, AssistantMode.AUTONOMOUS_WITH_APPROVAL, null);
        handToAssistant();
        inbound(IDENTITY_ANSWER);
        inbound("quero pagar à vista");
        inbound("aceito O1");
        assertThat(outcomes()).last().isEqualTo("APPROVAL_REQUESTED");
        UUID proposalId = UUID.fromString(lastTurnColumn("proposal_id::text"));
        assertThat(count("select count(*) from negotiation.proposal where id = ? and status = 'ACCEPTED'", proposalId))
                .isEqualTo(1);
        assertThat(conversation().ownerType()).isEqualTo("QUEUE");
        assertThat(lastAssistantText()).contains("aceite foi registrado");
    }

    @Test
    void shadowModeOnlyDraftsForTheOperator() {
        scenario("ast-shadow", "800.00");
        publishPolicy("sombra", creditorA, AssistantMode.SHADOW, null);
        inbound("quanto devo?");
        assertThat(outcomes()).containsExactly("DRAFTED");
        assertThat(assistantMessages()).isEmpty();
        ConversationDetail detail = system(() -> conversations.get(conversationId));
        assertThat(detail.conversation().ownerType()).isEqualTo("USER");
        assertThat(detail.pendingDrafts()).singleElement().satisfies(d -> {
            assertThat(d.mode()).isEqualTo("SHADOW");
            assertThat(d.authorType()).isEqualTo("ASSISTANT");
            assertThat(d.content()).contains("saldo atualizado");
        });
    }

    @Test
    void policyCanForceTheAdvancedModelInEveryCall() {
        scenario("ast-tier", "800.00");
        PolicyView policy = publishPolicy("piloto", creditorA, AssistantMode.SHADOW, null, ModelRouting.ADVANCED_ONLY);
        assertThat(policy.settings().modelRouting()).isEqualTo(ModelRouting.ADVANCED_ONLY);
        assertThat(count("select count(*) from shared.business_event where event_type = 'policy.published' "
                + "and attributes ->> 'modelRouting' = 'ADVANCED_ONLY' and reference_id = ?", policy.id())).isEqualTo(1);
        inbound("quanto devo?");
        assertThat(outcomes()).containsExactly("DRAFTED");
        assertThat(modelTiers("assistant.draft_proposed")).singleElement().satisfies(t -> {
            String[] p = t.split("/");
            assertThat(p[0]).isEqualTo("ADVANCED");
            assertThat(p[1]).isEqualTo(p[2]);
        });
    }

    @Test
    void vulnerabilityTriggerTransfersBeforeTheModel() {
        scenario("ast-vuln", "800.00");
        publishPolicy("vuln", creditorA, AssistantMode.AUTONOMOUS, null);
        handToAssistant();
        inbound("estou desempregado e com câncer, não consigo pagar");
        assertThat(outcomes()).containsExactly("HANDED_OFF");
        assertThat(lastTurnColumn("handoff_reason")).isEqualTo("VULNERABILITY");
        assertThat(lastTurnColumn("trigger_source")).isEqualTo("RULE");
        assertThat(lastTurnColumn("model_calls::text")).isEqualTo("0");
        ConversationDetail detail = system(() -> conversations.get(conversationId));
        assertThat(detail.conversation().ownerType()).isEqualTo("QUEUE");
        assertThat(detail.conversation().requiredProfile()).isEqualTo("SENIOR");
        assertThat(lastAssistantText()).contains("assistente virtual").contains("transferir");
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.handoff' "
                + "and reference_id = ?", conversationId)).isEqualTo(1);
    }

    @Test
    void gateDenialIsRecordedAndNothingIsSent() {
        scenario("ast-gate", "800.00");
        publishPolicy("porteiro", creditorA, AssistantMode.AUTONOMOUS, null);
        handToAssistant();
        asAdmin(() -> restrictions.create(new CreateRestrictionCommand(debtor, null, "WHATSAPP", "DEBTOR_REQUEST", null,
                null, "Pedido do devedor por telefone", "DEBTOR", null, null)));
        inbound("oi");
        assertThat(outcomes()).containsExactly("SEND_BLOCKED");
        assertThat(lastTurnColumn("error_code")).isEqualTo("CONTACT_DENIED");
        assertThat(assistantMessages()).allMatch(m -> "FAILED".equals(m.status()));
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.reply_blocked'"))
                .isEqualTo(1);
    }

    @Test
    void guardrailBlocksInventedValuesAndSamplesTheTurn() {
        scenario("ast-guard", "800.00");
        publishPolicy("guarda", creditorA, AssistantMode.AUTONOMOUS, null);
        handToAssistant();
        inbound(IDENTITY_ANSWER);
        inbound("me dá um preço camarada [sandbox:valor-inventado]");
        assertThat(outcomes()).last().isEqualTo("HANDED_OFF");
        assertThat(lastTurnColumn("handoff_reason")).isEqualTo("GUARDRAIL");
        assertThat(lastTurnColumn("guardrails::text")).contains("VALUE_NOT_FROM_ENGINE");
        assertThat(assistantMessages()).noneMatch(m -> m.content().contains("R$ 1,00"));
        assertThat(conversation().ownerType()).isEqualTo("QUEUE");

        // amostra de revisão: sempre para guardrail; quem publicou a política não avalia
        var queue = asSupervisor(() -> reviews.queue(null, null, PageQuery.of(0, 50)));
        var guardrailReview = queue.content().stream().filter(r -> "GUARDRAIL".equals(r.sampleReason())).findFirst()
                .orElseThrow();
        var reviewed = asSupervisor(() -> reviews.submit(guardrailReview.id(),
                new ReviewCommand(5, "ADEQUATE", List.of("ACCURACY"), null)));
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        var quality = asSupervisor(() -> reviews.quality(new QualityQuery(null, null, creditorA, null)));
        assertThat(quality.handoffsByReason()).containsEntry("GUARDRAIL", 1L);
        assertThat(quality.guardrails()).containsKey("VALUE_NOT_FROM_ENGINE");
        assertThat(quality.reviewed()).isEqualTo(1);
        assertThat(quality.cost().signum()).isPositive();
    }

    @Test
    void creditorScopeAndRowLevelSecurity() {
        scenario("ast-scope", "800.00");
        publishPolicy("escopo", creditorA, AssistantMode.AUTONOMOUS, null);
        handToAssistant();
        inbound("oi");
        UUID turnId = UUID.fromString(lastTurnColumn("id::text"));

        // supervisor só do credor B: trilha do credor A invisível (404) e política do credor A recusada (403)
        CreditorScope onlyB = CreditorScope.of(Set.of(creditorB));
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.SUPERVISOR, onlyB, () -> trail.turn(turnId)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.SUPERVISOR, onlyB, () -> trail.turns(conversationId,
                PageQuery.of(0, 10)))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.GESTOR, onlyB, () -> assistantPolicies.createDraft(
                new CreatePolicyCommand(creditorA, null, "outra", "Outra", PolicySettings.defaults(AssistantMode.SHADOW)))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(as(Ids.newId(), DefaultRoles.GESTOR, onlyB, () -> assistantPolicies.list())).isEmpty();
        // operador não tem ASSISTANT_REVIEW
        assertThatThrownBy(() -> asOperator(operatorX, () -> trail.turn(turnId)))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // RLS: outra assessoria não vê a trilha
        UUID otherTenant = Ids.newId();
        long visible = tenantExecution.callInTenant(otherTenant, "test.rls", () -> jdbcTemplate.queryForObject(
                "select count(*) from assistant.assistant_turn", Long.class));
        assertThat(visible).isZero();
        assertThat(count("select count(*) from assistant.assistant_turn")).isEqualTo(1);

        // append-only: a trilha e as chamadas de ferramenta não mudam
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update assistant.assistant_turn set outcome = 'REPLIED' where id = ?", turnId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "delete from assistant.assistant_turn where id = ?", turnId))).isInstanceOf(DataAccessException.class);
        // descarte do conteúdo é a única alteração aceita
        assertThat(system(() -> jdbcTemplate.update("update assistant.assistant_turn set input_text_encrypted = null, "
                + "context_encrypted = null, output_text_encrypted = null, content_purged_at = now() where id = ?",
                turnId))).isEqualTo(1);
    }

    @Test
    void portfolioDisableTransfersConversationsImmediately() {
        scenario("ast-off", "800.00");
        publishPolicy("geral", null, AssistantMode.AUTONOMOUS, null);
        handToAssistant();
        PolicyView off = asAdmin(() -> assistantPolicies.disablePortfolio(portfolioA, "Revisão do roteiro"));
        assertThat(off.mode()).isEqualTo("OFF");
        br.com.gestorcobranca.shared.jobs.JobDrain.drain(applicationContext, jdbcTemplate, "assistant.");
        assertThat(conversation().ownerType()).isEqualTo("QUEUE");
        assertThat(asAdmin(() -> assistantPolicies.effective(creditorA, portfolioA))).get()
                .extracting(PolicyView::mode).isEqualTo("OFF");
    }
}
