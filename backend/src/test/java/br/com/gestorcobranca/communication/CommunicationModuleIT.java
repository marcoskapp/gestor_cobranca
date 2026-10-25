package br.com.gestorcobranca.communication;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationDetail;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.DraftView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.InboxFilter;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ProposeDraftCommand;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.communication.HandoffRequested;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxos críticos do módulo communication contra PostgreSQL real: envio autorizado pelo porteiro e
 * entrega simulada, bloqueio na verificação prévia e no envio, janela do WhatsApp, opt-out por
 * palavra-chave, dono do atendimento alinhado ao caso, transferência por evento, rascunhos do
 * assistente, escopo por credor e RLS.
 */
class CommunicationModuleIT extends CommunicationTestSupport {

    UUID debtor;
    CaseView kase;

    @BeforeEach
    void setUp() {
        provision("com");
        debtor = debtor("529.982.247-25");
        kase = loadCase(creditorA, portfolioA, debtor, "500.00");
        publishedTemplate(null, "COBRANCA", "WHATSAPP");
    }

    private SendMessageCommand template(UUID conversationId, String key) {
        return new SendMessageCommand(conversationId, conversationId == null ? debtor : null,
                conversationId == null ? creditorA : null, null, conversationId == null ? "WHATSAPP" : null, null,
                "COBRANCA", Map.of("nome", "Maria", "valor", "500.00"), null, null, key);
    }

    private SendMessageCommand text(UUID conversationId, String text) {
        return new SendMessageCommand(conversationId, null, null, null, null, null, null, null, text, null, null);
    }

    private MessageView message(UUID id) {
        return system(() -> messages.get(id));
    }

    private ConversationView conversation(UUID id) {
        return system(() -> conversations.get(id).conversation());
    }

    @Test
    void operatorSendsTemplateThroughGateAndSandboxDelivers() {
        MessageView queued = asOperator(operatorX, () -> messages.send(template(null, "op-1")));
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.senderType()).isEqualTo("USER");
        assertThat(queued.content()).isEqualTo("Olá Maria, há um débito de R$ 500,00. Responda para negociar.");

        // o operador que envia assume a conversa e o caso
        ConversationView conv = conversation(queued.conversationId());
        assertThat(conv.caseId()).isEqualTo(kase.id());
        assertThat(conv.ownerType()).isEqualTo("USER");
        assertThat(conv.ownerUserId()).isEqualTo(operatorX);
        assertThat(system(() -> cases.get(kase.id())).assigneeUserId()).isEqualTo(operatorX);

        // idempotência do comando
        MessageView again = asOperator(operatorX, () -> messages.send(template(null, "op-1")));
        assertThat(again.id()).isEqualTo(queued.id());

        drainCommunication();
        MessageView delivered = message(queued.id());
        assertThat(delivered.status()).isEqualTo("READ");
        assertThat(delivered.sentAt()).isNotNull();
        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(delivered.sendAttempts()).isEqualTo(1);

        // tentativa registrada no compliance, com referência à mensagem
        assertThat(count("select count(*) from compliance.contact_attempt where reference_type = 'message' "
                + "and reference_id = ?", queued.id())).isEqualTo(1);
        // conteúdo criptografado no banco
        assertThat(count("select count(*) from communication.message where id = ? and content_encrypted like 'k1v2:%' "
                + "and content_encrypted not like '%Maria%'", queued.id())).isEqualTo(1);
        // interação registrada no caso pelo port do collection (após o commit, por evento)
        awaitIdle("communication");
        assertThat(count("select count(*) from collection.interaction where case_id = ? and source = 'COMMUNICATION' "
                + "and external_ref = ? and outcome_code = 'MESSAGE_SENT'", kase.id(), "message:" + queued.id()))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'message.sent' "
                + "and reference_id = ?", queued.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'message.read' "
                + "and reference_id = ?", queued.id())).isEqualTo(1);
    }

    @Test
    void gateBlocksAtRequestAndAtSendTime() {
        // restrição antes do pedido: verificação prévia registra a falha, sem job
        UUID restriction = system(() -> restrictions.create(new CreateRestrictionCommand(debtor, null, "WHATSAPP",
                "DEBTOR_REQUEST", null, null, "Pediu para não receber", "DEBTOR", null, null)).id());
        MessageView blocked = asOperator(operatorX, () -> messages.send(template(null, null)));
        assertThat(blocked.status()).isEqualTo("FAILED");
        assertThat(blocked.failureCode()).isEqualTo("CONTACT_DENIED");
        assertThat(blocked.failureDetail()).contains("RESTRICTED_CHANNEL");
        assertThat(count("select count(*) from shared.processing_job where type = 'communication.send' "
                + "and payload ->> 'messageId' = ?", blocked.id().toString())).isZero();
        assertThat(count("select count(*) from shared.business_event where event_type = 'message.blocked' "
                + "and reference_id = ?", blocked.id())).isEqualTo(1);

        // pedido aceito e restrição criada antes do envio: o job é negado pelo porteiro
        system(() -> restrictions.revoke(restriction, "Revogado"));
        MessageView queued = asOperator(operatorX, () -> messages.send(template(null, null)));
        assertThat(queued.status()).isEqualTo("QUEUED");
        system(() -> restrictions.create(new CreateRestrictionCommand(debtor, creditorA, null, "CREDITOR_REQUEST", null,
                null, "Credor suspendeu", "CREDITOR", null, null)));
        drainCommunication();
        MessageView denied = message(queued.id());
        assertThat(denied.status()).isEqualTo("FAILED");
        assertThat(denied.failureCode()).isEqualTo("CONTACT_DENIED");
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ?", queued.id())).isZero();
    }

    @Test
    void whatsappWindowAndOptOutByKeyword() {
        MessageView first = asOperator(operatorX, () -> messages.send(template(null, null)));
        drainCommunication();
        UUID conversationId = first.conversationId();
        // fora da janela de 24 h só template
        assertThatThrownBy(() -> asOperator(operatorX, () -> messages.send(text(conversationId, "Podemos conversar?"))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "message.whatsapp_window_closed");

        // resposta do devedor abre a janela e a espera por resposta
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511987654321", "Quero negociar");
            return null;
        });
        ConversationView afterReply = conversation(conversationId);
        assertThat(afterReply.sessionWindowOpen()).isTrue();
        assertThat(afterReply.awaitingSince()).isNotNull();
        assertThat(afterReply.unreadCount()).isEqualTo(1);
        MessageView reply = asOperator(operatorX, () -> messages.send(text(conversationId, "Claro, posso ajudar.")));
        assertThat(reply.status()).isEqualTo("QUEUED");
        assertThat(conversation(conversationId).awaitingSince()).isNull();
        drainCommunication();
        awaitIdle("communication");
        assertThat(count("select count(*) from collection.interaction where case_id = ? and outcome_code = 'MESSAGE_RECEIVED'",
                kase.id())).isEqualTo(1);

        // palavra de descadastro: opt-out do contato no compliance, imediatamente
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511987654321", "SAIR");
            return null;
        });
        assertThat(count("select count(*) from compliance.contact_restriction where type = 'OPT_OUT' "
                + "and source = 'PROVIDER_STOP_KEYWORD' and channel = 'WHATSAPP' and status = 'ACTIVE'")).isEqualTo(1);
        MessageView afterOptOut = asOperator(operatorX, () -> messages.send(text(conversationId, "Tudo bem.")));
        assertThat(afterOptOut.status()).isEqualTo("FAILED");
        assertThat(afterOptOut.failureCode()).isEqualTo("CONTACT_DENIED");
    }

    @Test
    void handoffFromAssistantByEventRoutesToQueueWithProfile() {
        MessageView first = asOperator(operatorX, () -> messages.send(template(null, null)));
        drainCommunication();
        UUID conversationId = first.conversationId();
        asOperator(operatorX, () -> conversations.returnToAssistant(conversationId, "Assistente conduz"));
        assertThat(conversation(conversationId).ownerType()).isEqualTo("ASSISTANT");
        assertThat(system(() -> cases.get(kase.id())).ownerType()).isEqualTo("ASSISTANT");

        // o operador não envia sem assumir; o assistente envia porque conduz
        UUID policy = Ids.newId();
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511987654321", "Qual o desconto?");
            return null;
        });
        MessageView assistantReply = asAssistant(policy, creditorA,
                () -> messages.send(text(conversationId, "Posso oferecer condições especiais.")));
        assertThat(assistantReply.senderType()).isEqualTo("ASSISTANT");
        assertThat(assistantReply.senderId()).isEqualTo(policy);

        // negotiation (proposta acima da alçada) pede transferência por evento
        publishAndAwait(new HandoffRequested(Ids.newId(), tenant, Instant.now(), kase.id(), debtor, creditorA, null,
                "REQUIRES_APPROVAL", "SUPERVISOR", "Proposta de 40% acima da alçada do assistente", "proposal",
                Ids.newId(), "ASSISTANT", policy), "communication.handoff-requested");
        ConversationDetail detail = system(() -> conversations.get(conversationId));
        assertThat(detail.conversation().ownerType()).isEqualTo("QUEUE");
        assertThat(detail.conversation().requiredProfile()).isEqualTo("SUPERVISOR");
        assertThat(detail.handoffs()).last().satisfies(h -> {
            assertThat(h.kind()).isEqualTo("ESCALATION");
            assertThat(h.fromOwnerType()).isEqualTo("ASSISTANT");
            assertThat(h.reasonCode()).isEqualTo("REQUIRES_APPROVAL");
            assertThat(h.summary()).contains("acima da alçada");
        });
        assertThat(system(() -> cases.get(kase.id())).ownerType()).isIn("QUEUE", "NONE");
        assertThat(count("select count(*) from shared.business_event where event_type = 'assistant.handoff' "
                + "and reference_id = ?", conversationId)).isEqualTo(1);
        // o assistente não envia mais
        assertThatThrownBy(() -> asAssistant(policy, creditorA, () -> messages.send(text(conversationId, "Oi"))))
                .hasFieldOrPropertyWithValue("code", "conversation.not_with_assistant");

        // fila por perfil na caixa de entrada; supervisor assume
        assertThat(asSupervisor(() -> conversations.inbox(new InboxFilter(null, null, null, null, null, "OPEN",
                Set.of("QUEUE"), null, null, "SUPERVISOR", null), PageQuery.of(0, 20))).content())
                .extracting(ConversationView::id).contains(conversationId);
        ConversationView taken = asSupervisor(() -> conversations.takeOver(conversationId, null));
        assertThat(taken.ownerUserId()).isEqualTo(supervisor);
        assertThat(system(() -> cases.get(kase.id())).assigneeUserId()).isEqualTo(supervisor);
        // outro operador não trabalha a conversa do supervisor
        assertThatThrownBy(() -> asOperator(operatorY, () -> conversations.takeOver(conversationId, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void assistantDraftsAreSentOrDiscardedByTheOperator() {
        MessageView first = asOperator(operatorX, () -> messages.send(template(null, null)));
        drainCommunication();
        UUID conversationId = first.conversationId();
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511987654321", "Oi, quero pagar");
            return null;
        });
        UUID policy = Ids.newId();
        DraftView draft = asAssistant(policy, creditorA, () -> conversations.proposeDraft(conversationId,
                new ProposeDraftCommand("SHADOW", "Podemos parcelar em 3x de R$ 170,00.", "oferta nível 1")));
        DraftView other = asAssistant(policy, creditorA, () -> conversations.proposeDraft(conversationId,
                new ProposeDraftCommand("COPILOT", "Ofereça 10% à vista.", null)));
        assertThat(system(() -> conversations.get(conversationId)).pendingDrafts()).hasSize(2);

        MessageView sent = asOperator(operatorX, () -> conversations.sendDraft(conversationId, draft.id(),
                "Podemos parcelar em 3x de R$ 170,00 sem juros."));
        assertThat(sent.status()).isEqualTo("QUEUED");
        assertThat(sent.senderType()).isEqualTo("USER");
        DraftView discarded = asOperator(operatorX, () -> conversations.discardDraft(conversationId, other.id()));
        assertThat(discarded.status()).isEqualTo("DISCARDED");
        assertThat(count("select count(*) from communication.conversation_draft where id = ? and status = 'SENT' "
                + "and edited", draft.id())).isEqualTo(1);
        assertThatThrownBy(() -> asOperator(operatorX, () -> conversations.discardDraft(conversationId, draft.id())))
                .hasFieldOrPropertyWithValue("code", "draft.not_pending");
    }

    @Test
    void creditorScopeAndRowLevelSecurity() {
        MessageView first = asOperator(operatorX, () -> messages.send(template(null, null)));
        UUID conversationId = first.conversationId();
        // Z só enxerga o credor B
        assertThatThrownBy(() -> asOperator(operatorZ, () -> conversations.get(conversationId)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperator(operatorZ, () -> messages.get(first.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperator(operatorZ, () -> messages.send(template(null, null))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asOperator(operatorZ, () -> conversations.inbox(new InboxFilter(null, null, null, null, null, null,
                null, null, null, null, null), PageQuery.of(0, 50))).content()).isEmpty();
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' "
                + "and details ->> 'resource' = 'conversation'")).isGreaterThanOrEqualTo(1);
        // conta padrão (sem credor) só para quem tem todos os credores
        assertThatThrownBy(() -> asOperator(operatorZ, () -> channels.get(whatsapp.id())))
                .isInstanceOf(ResourceNotFoundException.class);

        // RLS: outra assessoria não vê nada do módulo
        UUID mine = tenant;
        provision("rls");
        UUID other = tenant;
        tenant = mine;
        for (String table : List.of("message", "conversation", "conversation_handoff", "channel_account",
                "message_template")) {
            long visible = tenantExecution.callInTenant(other, "test", () -> jdbcTemplate.queryForObject(
                    "select count(*) from communication." + table + " where tenant_id = ?", Long.class, mine));
            assertThat(visible).as(table).isZero();
        }
        // sem tenant definido nenhuma linha é visível
        assertThat(jdbcTemplate.queryForObject("select count(*) from communication.message", Long.class)).isZero();
        // transferências são imutáveis
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update communication.conversation_handoff set reason_code = 'X'"))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void unidentifiedContactGoesToQueueAndMustBeIdentifiedBeforeReplying() {
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511912345678", "Recebi uma cobrança, quem é?");
            return null;
        });
        ConversationView unknown = asSupervisor(() -> conversations.inbox(new InboxFilter(null, null, null, null, null,
                "OPEN", Set.of("QUEUE"), null, null, null, true), PageQuery.of(0, 20))).content().stream()
                .filter(c -> c.debtorId() == null).findFirst().orElseThrow();
        assertThat(unknown.contactMasked()).doesNotContain("912345678");
        assertThatThrownBy(() -> asSupervisor(() -> messages.send(text(unknown.id(), "Olá"))))
                .hasFieldOrPropertyWithValue("code", "conversation.unidentified");
        // o contato não é do devedor: identificação recusada
        assertThatThrownBy(() -> asSupervisor(() -> conversations.identify(unknown.id(), debtor, creditorA)))
                .hasFieldOrPropertyWithValue("code", "conversation.contact_not_of_debtor");
    }

    @Test
    void providerRejectionFailsAndTransientFailuresRetryUntilDead() {
        MessageView first = asOperator(operatorX, () -> messages.send(template(null, null)));
        drainCommunication();
        UUID conversationId = first.conversationId();
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511987654321", "Pode falar");
            return null;
        });

        MessageView rejected = asOperator(operatorX, () -> messages.send(text(conversationId, "Texto [sandbox:reject]")));
        drainCommunication();
        assertThat(message(rejected.id()).status()).isEqualTo("FAILED");
        assertThat(message(rejected.id()).failureCode()).isEqualTo("SANDBOX_REJECTED");

        MessageView flaky = asOperator(operatorX, () -> messages.send(text(conversationId, "Texto [sandbox:transient]")));
        drainCommunication();
        // primeira tentativa falhou: job reagendado, mensagem ainda na fila e tentativa desfeita no compliance
        assertThat(message(flaky.id()).status()).isEqualTo("QUEUED");
        assertThat(count("select count(*) from shared.processing_job where type = 'communication.send' "
                + "and payload ->> 'messageId' = ? and status = 'PENDING' and attempts = 1", flaky.id().toString()))
                .isEqualTo(1);
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ?", flaky.id())).isZero();

        // última tentativa: mensagem FAILED/RETRIES_EXHAUSTED e job DEAD
        jdbcTemplate.update("update shared.processing_job set run_at = now(), attempts = 5 where type = 'communication.send' "
                + "and payload ->> 'messageId' = ?", flaky.id().toString());
        drainCommunication();
        MessageView exhausted = message(flaky.id());
        assertThat(exhausted.status()).isEqualTo("FAILED");
        assertThat(exhausted.failureCode()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(exhausted.sendAttempts()).isEqualTo(6);
        assertThat(count("select count(*) from shared.processing_job where type = 'communication.send' "
                + "and payload ->> 'messageId' = ? and status = 'DEAD'", flaky.id().toString())).isEqualTo(1);
    }

    @Test
    void templatesAreVersionedPerCreditorAndCreditorTemplateWins() {
        var v1 = publishedTemplate(creditorA, "COBRANCA", "WHATSAPP");
        var v2 = publishedTemplate(creditorA, "COBRANCA", "WHATSAPP");
        assertThat(v2.templateVersion()).isEqualTo(v1.templateVersion() + 1);
        assertThat(system(() -> templates.get(v1.id())).status()).isEqualTo("RETIRED");
        // credor A usa a própria versão; o template da assessoria vale para quem não tem
        MessageView sent = asOperator(operatorX, () -> messages.send(template(null, null)));
        assertThat(sent.templateId()).isEqualTo(v2.id());
        // rascunho de WhatsApp sem aprovação não publica; variável com dado pessoal é recusada
        var draft = asManager(() -> templates.createDraft(new TemplateUseCasesFixtures().draft(creditorA)));
        assertThatThrownBy(() -> asManager(() -> templates.publish(draft.id())))
                .hasFieldOrPropertyWithValue("code", "template.not_approved");
        assertThatThrownBy(() -> asOperator(operatorX, () -> messages.send(new SendMessageCommand(null, debtor, creditorA,
                null, "WHATSAPP", null, "COBRANCA", Map.of("nome", "Maria", "valor", "529.982.247-25"), null, null, null))))
                .hasFieldOrPropertyWithValue("code", "template.variable_money_invalid");
        // escopo: Z (credor B) não vê nem altera template do credor A
        assertThatThrownBy(() -> as(operatorZ, "GESTOR", br.com.gestorcobranca.shared.security.CreditorScope.of(
                Set.of(creditorB)), () -> templates.get(v2.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.audit_event where entity_type = 'message_template' "
                + "and action = 'message_template.published'")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void oneActiveAccountPerCreditorAndChannelWithCredentialsNeverReturned() {
        assertThatThrownBy(() -> asAdmin(() -> channels.create(new br.com.gestorcobranca.communication.application.port.in
                .ChannelAccountUseCases.CreateAccountCommand("WHATSAPP", "SANDBOX", null, "Outra", "x", null, null,
                Map.of())))).hasFieldOrPropertyWithValue("code", "channel_account.already_active");
        assertThatThrownBy(() -> asAdmin(() -> channels.create(new br.com.gestorcobranca.communication.application.port.in
                .ChannelAccountUseCases.CreateAccountCommand("WHATSAPP", "WHATSAPP_CLOUD", creditorA, "Banco A", "123",
                null, null, Map.of("accessToken", "t")))))
                .hasFieldOrPropertyWithValue("code", "channel_account.credential_required");
        assertThatThrownBy(() -> asAdmin(() -> channels.create(new br.com.gestorcobranca.communication.application.port.in
                .ChannelAccountUseCases.CreateAccountCommand("SMS", "WHATSAPP_CLOUD", null, "SMS", "123", null, null,
                Map.of())))).hasFieldOrPropertyWithValue("code", "channel_account.sms_provider_unavailable");
        var account = asAdmin(() -> channels.create(new br.com.gestorcobranca.communication.application.port.in
                .ChannelAccountUseCases.CreateAccountCommand("WHATSAPP", "WHATSAPP_CLOUD", creditorA, "Banco A", "123",
                null, null, Map.of("accessToken", "token-secreto", "appSecret", "s", "verifyToken", "v"))));
        var rotated = asAdmin(() -> channels.rotateCredentials(account.id(),
                Map.of("accessToken", "novo-token", "appSecret", "s2", "verifyToken", "v2")));
        assertThat(rotated.credentialKeys()).containsExactlyInAnyOrder("accessToken", "appSecret", "verifyToken");
        assertThat(rotated.toString()).doesNotContain("novo-token");
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? "
                + "and (before_state::text like '%token%' or after_state::text like '%token-secreto%' or metadata::text like '%novo-token%')",
                account.id())).isZero();
        // remetente por credor: envio do credor A passa a usar a conta do credor
        MessageView sent = asOperator(operatorX, () -> messages.send(template(null, null)));
        assertThat(count("select count(*) from communication.message where id = ? and channel_account_id = ?",
                sent.id(), account.id())).isEqualTo(1);
    }

    /** Rascunho de WhatsApp sem aprovação. */
    static final class TemplateUseCasesFixtures {
        br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.CreateTemplateCommand draft(UUID creditor) {
            return new br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.CreateTemplateCommand(
                    creditor, "LEMBRETE", "WHATSAPP", new br.com.gestorcobranca.communication.application.port.in
                    .TemplateUseCases.TemplateContent("Lembrete", "REMINDER", null, "Olá {{nome}}",
                    List.of(new br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput(
                            "nome", "FIRST_NAME", true, null)), null, "lembrete"));
        }
    }
}
