package br.com.gestorcobranca.communication;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.InboxCounts;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.InboxFilter;
import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases;
import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases.InboxSignal;
import br.com.gestorcobranca.communication.application.port.in.InboxRealtimeUseCases.Subscription;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.communication.application.port.in.ResponseTargetUseCases;
import br.com.gestorcobranca.communication.application.port.in.ResponseTargetUseCases.ResponseTargetView;
import br.com.gestorcobranca.communication.application.service.CommunicationTestHooks;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lacunas de API da caixa de entrada contra PostgreSQL real: nomes de devedores em lote, linha do
 * tempo do devedor em todos os canais, contagem por aba, metas de primeira resposta (SLA) com
 * varredura de estouros, e avisos em tempo real (gatilho → LISTEN/NOTIFY → assinaturas filtradas
 * pelo escopo, e o stream SSE autenticado) — com permissões, escopo por credor e RLS.
 */
class InboxApiIT extends CommunicationTestSupport {

    @Autowired
    WebApplicationContext webApplicationContext;
    @Autowired
    ResponseTargetUseCases responseTargets;
    @Autowired
    InboxRealtimeUseCases realtime;

    MockMvc mvc;
    UUID debtor;
    CaseView caseA;
    CaseView caseB;

    @BeforeEach
    void setUp() {
        provision("inbox");
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        debtor = debtor("529.982.247-25");
        caseA = loadCase(creditorA, portfolioA, debtor, "500.00");
        caseB = loadCase(creditorB, portfolioB, debtor, "300.00");
        publishedTemplate(null, "COBRANCA", "WHATSAPP");
    }

    private SendMessageCommand template(UUID creditorId, String key) {
        return new SendMessageCommand(null, debtor, creditorId, null, "WHATSAPP", null, "COBRANCA",
                Map.of("nome", "Maria", "valor", "500.00"), null, null, key);
    }

    private ActorAuthentication auth(UUID userId, Set<Permission> permissions, CreditorScope scope) {
        return ActorAuthentication.of(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "TESTE", permissions,
                scope));
    }

    private ActorAuthentication operator(UUID userId) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.OPERADOR))
                .findFirst().orElseThrow().permissions();
        return auth(userId, permissions, userId.equals(operatorZ) ? CreditorScope.of(Set.of(creditorB)) : CreditorScope.ALL);
    }

    @Test
    void debtorNamesInBatchWithWorkPermissionAndCreditorScope() throws Exception {
        UUID onlyA = debtor("111.444.777-35");
        loadCase(creditorA, portfolioA, onlyA, "100.00");
        String ids = debtor + "," + onlyA + "," + Ids.newId();

        // somente INBOX_VIEW (sem DEBTOR_VIEW): só id, nome e situação, nunca documento
        mvc.perform(get("/api/v1/debtors/names").param("ids", ids)
                        .with(authentication(auth(operatorX, Set.of(Permission.INBOX_VIEW), CreditorScope.ALL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].maskedDocument").doesNotExist())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        // escopo: operador do credor B não vê o devedor só do credor A (omitido, sem erro)
        mvc.perform(get("/api/v1/debtors/names").param("ids", ids).with(authentication(operator(operatorZ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(debtor.toString()));
        // sem permissão de trabalho
        mvc.perform(get("/api/v1/debtors/names").param("ids", ids)
                        .with(authentication(auth(operatorX, Set.of(Permission.TEMPLATE_MANAGE), CreditorScope.ALL))))
                .andExpect(status().isForbidden());
        // limite de ids por chamada
        String tooMany = String.join(",", IntStream.range(0, 201).mapToObj(i -> Ids.newId().toString()).toList());
        mvc.perform(get("/api/v1/debtors/names").param("ids", tooMany).with(authentication(operator(operatorX))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("debtor.names_limit"));
    }

    @Test
    void debtorTimelineAcrossConversationsRespectsScopeAndRls() throws Exception {
        MessageView a1 = asOperator(operatorX, () -> messages.send(template(creditorA, "a1")));
        MessageView b1 = asOperator(operatorX, () -> messages.send(template(creditorB, "b1")));
        MessageView a2 = asOperator(operatorX, () -> messages.send(template(creditorA, "a2")));
        assertThat(a1.conversationId()).isEqualTo(a2.conversationId()).isNotEqualTo(b1.conversationId());

        // todas as conversas do devedor, mais recentes primeiro
        assertThat(asOperator(operatorX, () -> messages.listByDebtor(debtor, null, PageQuery.of(0, 50))).content())
                .extracting(MessageView::id).containsExactly(a2.id(), b1.id(), a1.id());
        assertThat(asOperator(operatorX, () -> messages.listByDebtor(debtor, creditorA, PageQuery.of(0, 50))).content())
                .extracting(MessageView::id).containsExactly(a2.id(), a1.id());
        // escopo por credor: Z (credor B) só vê a conversa de B; pedir o credor A devolve vazio
        assertThat(asOperator(operatorZ, () -> messages.listByDebtor(debtor, null, PageQuery.of(0, 50))).content())
                .extracting(MessageView::id).containsExactly(b1.id());
        assertThat(asOperator(operatorZ, () -> messages.listByDebtor(debtor, creditorA, PageQuery.of(0, 50))).content())
                .isEmpty();

        // HTTP: paginação e permissão de caixa de entrada
        mvc.perform(get("/api/v1/communication/debtors/" + debtor + "/messages").param("size", "2")
                        .with(authentication(operator(operatorX))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].id").value(a2.id().toString()));
        mvc.perform(get("/api/v1/communication/debtors/" + debtor + "/messages")
                        .with(authentication(auth(operatorX, Set.of(Permission.DEBTOR_VIEW), CreditorScope.ALL))))
                .andExpect(status().isForbidden());

        // RLS: outra assessoria não enxerga as mensagens do devedor
        UUID mine = tenant;
        UUID mineZ = operatorZ;
        provision("inbox-rls");
        UUID other = tenant;
        assertThat(tenantExecution.callAs(new Actor(Actor.ActorType.USER, operatorX, other, Ids.newId(), "OPERADOR",
                Set.of(Permission.INBOX_VIEW), CreditorScope.ALL), () -> messages.listByDebtor(debtor, null,
                PageQuery.of(0, 50))).content()).isEmpty();
        tenant = mine;
        operatorZ = mineZ;
    }

    @Test
    void tabCountsInOneQueryWithScopeAndSlaTargets() throws Exception {
        MessageView a = asOperator(operatorX, () -> messages.send(template(creditorA, "ca")));
        MessageView b = asOperator(operatorY, () -> messages.send(template(creditorB, "cb")));
        Instant now = Instant.now();
        // devedor respondeu nas duas conversas: A há 70 min (estourou 60 min do WhatsApp), B há 20 min (aviso)
        awaiting(a.conversationId(), now.minus(Duration.ofMinutes(70)));
        awaiting(b.conversationId(), now.minus(Duration.ofMinutes(20)));

        InboxCounts x = asOperator(operatorX, () -> conversations.counts(null, null));
        assertThat(x).isEqualTo(new InboxCounts(1, 0, 0, 2, 1));
        assertThat(asOperator(operatorX, () -> conversations.counts("WHATSAPP", true))).isEqualTo(new InboxCounts(1, 0, 0, 2, 1));
        assertThat(asOperator(operatorX, () -> conversations.counts("EMAIL", null))).isEqualTo(new InboxCounts(0, 0, 0, 0, 0));
        // Z só conta o credor B
        assertThat(asOperator(operatorZ, () -> conversations.counts(null, null))).isEqualTo(new InboxCounts(0, 0, 0, 1, 0));

        // SLA na listagem com os padrões
        ConversationView ca = conversation(a.conversationId());
        assertThat(ca.slaStatus()).isEqualTo("BREACHED");
        assertThat(ca.slaBreachAt()).isEqualTo(ca.awaitingSince().plus(Duration.ofMinutes(60)));
        assertThat(conversation(b.conversationId()).slaStatus()).isEqualTo("WARNING");

        // meta configurável por canal: 30/120 min para o WhatsApp
        assertThat(asOperator(operatorX, () -> responseTargets.list())).extracting(ResponseTargetView::channel,
                ResponseTargetView::warningMinutes, ResponseTargetView::breachMinutes, ResponseTargetView::customized)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WHATSAPP", 15, 60, false),
                        org.assertj.core.groups.Tuple.tuple("EMAIL", 240, 1440, false),
                        org.assertj.core.groups.Tuple.tuple("SMS", 15, 60, false));
        assertThatThrownBy(() -> asOperator(operatorX, () -> responseTargets.update("WHATSAPP", 30, 120)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asSupervisor(() -> responseTargets.update("WHATSAPP", 120, 30)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("response_target.warning_after_breach");
        ResponseTargetView updated = asSupervisor(() -> responseTargets.update("WHATSAPP", 30, 120));
        assertThat(updated.customized()).isTrue();
        assertThat(count("select count(*) from shared.audit_event where action = 'response_target.updated'")).isEqualTo(1);
        assertThat(conversation(a.conversationId()).slaStatus()).isEqualTo("WARNING");
        assertThat(conversation(b.conversationId()).slaStatus()).isEqualTo("OK");
        assertThat(asOperator(operatorX, () -> conversations.counts(null, null)).breached()).isZero();

        // HTTP
        mvc.perform(get("/api/v1/communication/conversations/counts").param("awaitingReply", "true")
                        .with(authentication(operator(operatorX))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(1))
                .andExpect(jsonPath("$.all").value(2));
        mvc.perform(get("/api/v1/communication/response-targets").with(authentication(operator(operatorZ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].warningMinutes").value(30));
        // alteração exige INBOX_MANAGE e acesso a todos os credores
        mvc.perform(put("/api/v1/communication/response-targets/SMS").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warningMinutes\":10,\"breachMinutes\":30}")
                        .with(authentication(auth(operatorZ, Set.of(Permission.INBOX_MANAGE, Permission.INBOX_VIEW),
                                CreditorScope.of(Set.of(creditorB))))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/communication/response-targets/SMS").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warningMinutes\":10,\"breachMinutes\":99999}")
                        .with(authentication(auth(supervisor, Set.of(Permission.INBOX_MANAGE), CreditorScope.ALL))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void slaScanCountsEachBreachOnce() {
        MessageView a = asOperator(operatorX, () -> messages.send(template(creditorA, "sa")));
        assertThat(CommunicationTestHooks.scanSla(applicationContext, tenant)).isZero();
        // a varredura anterior ficou 10 min atrás; a conversa aguarda há 65 min (estourou há 5 min)
        Instant now = Instant.now();
        system(() -> jdbcTemplate.update("update communication.sla_scan_cursor set scanned_until = ?",
                Timestamp.from(now.minus(Duration.ofMinutes(10)))));
        awaiting(a.conversationId(), now.minus(Duration.ofMinutes(65)));
        assertThat(CommunicationTestHooks.scanSla(applicationContext, tenant)).isEqualTo(1);
        assertThat(CommunicationTestHooks.scanSla(applicationContext, tenant)).isZero();
        assertThat(count("select count(*) from shared.business_event where event_type = 'conversation.sla_breached' "
                + "and reference_id = ? and channel = 'WHATSAPP'", a.conversationId())).isEqualTo(1);
    }

    @Test
    void realtimeSignalsReachOnlySubscribersInScope() throws Exception {
        List<InboxSignal> toX = new CopyOnWriteArrayList<>();
        List<InboxSignal> toZ = new CopyOnWriteArrayList<>();
        Subscription x = asOperator(operatorX, () -> realtime.subscribe(toX::add));
        Subscription z = asOperator(operatorZ, () -> realtime.subscribe(toZ::add));
        // assinatura exige INBOX_VIEW
        assertThatThrownBy(() -> tenantExecution.callAs(new Actor(Actor.ActorType.USER, operatorY, tenant, Ids.newId(),
                "X", Set.of(Permission.DEBTOR_VIEW), CreditorScope.ALL), () -> realtime.subscribe(s -> { })))
                .isInstanceOf(ScopeAccessDeniedException.class);

        MessageView a = asOperator(operatorX, () -> messages.send(template(creditorA, "ra")));
        awaitTrue("aviso da conversa A para X", () -> toX.stream().anyMatch(s -> a.conversationId().equals(s.conversationId())));
        MessageView b = asOperator(operatorX, () -> messages.send(template(creditorB, "rb")));
        awaitTrue("aviso da conversa B para Z", () -> toZ.stream().anyMatch(s -> b.conversationId().equals(s.conversationId())));
        // Z (credor B) nunca recebe avisos da conversa do credor A
        assertThat(toZ).extracting(InboxSignal::conversationId).doesNotContain(a.conversationId());

        // assinatura encerrada não recebe mais nada; outra assessoria não recebe avisos desta
        x.close();
        int before = toX.size();
        drainCommunication();
        awaitTrue("entrega simulada vista por Z", () -> toZ.size() >= 2);
        assertThat(toX).hasSize(before);
        z.close();

        // stream SSE: permissão e evento inicial
        mvc.perform(get("/api/v1/communication/inbox/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(authentication(auth(operatorX, Set.of(Permission.DEBTOR_VIEW), CreditorScope.ALL))))
                .andExpect(status().isForbidden());
        MvcResult stream = mvc.perform(get("/api/v1/communication/inbox/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(authentication(operator(operatorX))))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("event:ready").contains("retry:5000");
        asOperator(operatorX, () -> messages.send(template(creditorA, "rs")));
        awaitTrue("aviso no stream SSE", () -> {
            try {
                return stream.getResponse().getContentAsString().contains("event:conversation");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void awaiting(UUID conversationId, Instant since) {
        system(() -> jdbcTemplate.update("update communication.conversation set awaiting_since = ?, last_inbound_at = ? "
                + "where id = ?", Timestamp.from(since), Timestamp.from(since), conversationId));
    }

    private ConversationView conversation(UUID id) {
        return asSupervisor(() -> conversations.inbox(new InboxFilter(null, null, null, null, null, "OPEN", null, null,
                null, null, null), PageQuery.of(0, 50))).content().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow();
    }
}
