package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.CommunicationTestSupport;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.AnswerView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.AskCommand;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.CardView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.FeedbackCommand;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskExceptions;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases.TicketView;
import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskModelPort;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelRequest;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.CreditorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chat de suporte contra PostgreSQL real, com o modelo simulado determinístico (gravando o que receberia) e o
 * GitHub substituído por um dublê — nenhuma chamada real. Cobre: consulta como o próprio usuário (permissão e escopo,
 * inclusive ferramenta pedida sem ter sido oferecida), minimização (o modelo nunca recebe nome nem documento),
 * trilha de acesso, mascaramento e idempotência do chamado, trava de repositório privado, recuperação de abertura
 * interrompida, falha do provedor, resposta mascarada, avaliação, retenção e imutabilidade no banco.
 */
@Import(HelpdeskIT.Doubles.class)
class HelpdeskIT extends CommunicationTestSupport {

    static final String CPF = "529.982.247-25";
    static final String NAME = "Devedor 529";

    @TestConfiguration
    static class Doubles {

        @Bean
        @Primary
        FakeTracker fakeTracker() {
            return new FakeTracker();
        }

        @Bean
        @Primary
        RecordingModel recordingModel(@Qualifier("br.com.gestorcobranca.helpdesk.infrastructure.provider.sandbox.SandboxHelpdeskModel") HelpdeskModelPort sandbox) {
            return new RecordingModel(sandbox);
        }
    }

    /** Modelo simulado com registro de cada requisição (para conferir a minimização). */
    static final class RecordingModel implements HelpdeskModelPort {
        final List<ModelRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final HelpdeskModelPort delegate;

        RecordingModel(HelpdeskModelPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public String provider() {
            return delegate.provider();
        }

        @Override
        public ModelResponse complete(ModelRequest request) {
            requests.add(request);
            return delegate.complete(request);
        }
    }

    /** GitHub em memória: issues criadas, visibilidade e falhas roteirizadas. */
    static final class FakeTracker implements TicketTracker {
        /** Números únicos no "repositório" durante toda a execução (como no GitHub). */
        private static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong();
        final List<NewIssue> created = Collections.synchronizedList(new ArrayList<>());
        final List<Long> numbers = Collections.synchronizedList(new ArrayList<>());
        volatile boolean requirePrivate;
        volatile boolean privateRepository;
        volatile boolean failAfterCreate;

        void reset() {
            created.clear();
            numbers.clear();
            requirePrivate = false;
            privateRepository = false;
            failAfterCreate = false;
        }

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public String repository() {
            return "marcoskapp/gestor_cobranca";
        }

        @Override
        public List<String> labels() {
            return List.of("suporte");
        }

        @Override
        public boolean requiresPrivateRepository() {
            return requirePrivate;
        }

        @Override
        public boolean repositoryIsPrivate() {
            return privateRepository;
        }

        @Override
        public Optional<CreatedIssue> findByMarker(String marker, Instant since) {
            synchronized (created) {
                for (int i = 0; i < created.size(); i++) {
                    if (created.get(i).body().contains(marker)) {
                        return Optional.of(issue(i));
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public CreatedIssue create(NewIssue issue) {
            created.add(issue);
            numbers.add(NEXT.incrementAndGet());
            if (failAfterCreate) {
                failAfterCreate = false;
                throw new TrackerException("unavailable", true, "tempo esgotado depois de criar", null);
            }
            return issue(created.size() - 1);
        }

        private CreatedIssue issue(int index) {
            long n = numbers.get(index);
            return new CreatedIssue(n, "https://github.com/marcoskapp/gestor_cobranca/issues/" + n);
        }
    }

    @Autowired
    HelpdeskChatUseCases chat;
    @Autowired
    HelpdeskTicketUseCases tickets;
    @Autowired
    FakeTracker tracker;
    @Autowired
    RecordingModel model;
    @Autowired
    ChatStore chatStore;
    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

    UUID debtor;
    CaseView kase;
    UUID integrationUser;

    @BeforeEach
    void setUp() {
        tracker.reset();
        model.requests.clear();
    }

    private void scenario(String prefix) {
        provision(prefix);
        debtor = debtor(CPF);
        kase = loadCase(creditorA, portfolioA, debtor, "800.00");
        Map<String, UUID> roleIds = new java.util.HashMap<>();
        system(() -> roles.list()).forEach((RoleManagementUseCases.RoleView r) -> roleIds.put(r.code(), r.id()));
        integrationUser = system(() -> userManagement.create(new CreateUserCommand("Integra Dor", "integra-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null,
                Set.of(roleIds.get(DefaultRoles.INTEGRACAO)), true, Set.of())).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", integrationUser));
    }

    private <T> T asX(Supplier<T> action) {
        return asOperator(operatorX, action);
    }

    private <T> T asIntegration(Supplier<T> action) {
        return as(integrationUser, DefaultRoles.INTEGRACAO, CreditorScope.ALL, action);
    }

    private long accessEvents(String type, String tool) {
        return count("select count(*) from shared.access_log where event_type = ? and details ->> 'tool' = ?", type, tool);
    }

    private String allModelInput() {
        StringBuilder sb = new StringBuilder();
        for (ModelRequest r : model.requests) {
            sb.append(r.contextDocument());
            r.messages().forEach(m -> sb.append('\n').append(m.content()));
        }
        return sb.toString();
    }

    @Test
    void debtorLookupRunsAsTheUserWithMinimizedModelInputAndAccessTrail() {
        scenario("hd-debtor");
        AnswerView first = asX(() -> chat.ask(new AskCommand(null, "collection.queue",
                "Qual a situação do devedor " + CPF + "?")));
        assertThat(first.answer().outcome()).isEqualTo("ANSWERED");
        CardView card = first.answer().cards().getFirst();
        assertThat(card.kind()).isEqualTo("DEBTOR");
        assertThat(card.ref()).isEqualTo("D1");
        assertThat(card.title()).isEqualTo(NAME);
        assertThat(card.subtitle()).isNotEqualTo(CPF).contains("*");
        assertThat(card.route()).isEqualTo("/devedores/" + debtor);
        assertThat(first.answer().text()).contains("D1");

        AnswerView second = asX(() -> chat.ask(new AskCommand(first.conversationId(), "collection.queue",
                "E os casos do D1?")));
        assertThat(second.answer().cards()).anySatisfy(c -> {
            assertThat(c.kind()).isEqualTo("CASE");
            assertThat(c.route()).isEqualTo("/cobranca/casos/" + kase.id());
        });

        // minimização: o modelo nunca recebeu nome, documento ou ids internos
        String input = allModelInput();
        assertThat(input).doesNotContain("529.982").doesNotContain("52998224725").doesNotContain(NAME)
                .doesNotContain(debtor.toString()).doesNotContain(kase.id().toString()).contains("[DOC1]");
        // contexto: tela atual com o manual e só as telas que a pessoa pode abrir
        String context = model.requests.getFirst().contextDocument();
        assertThat(context).contains("Estação de cobrança").contains("collection.queue")
                .doesNotContain("\"tela\":\"admin.users\"");
        assertThat(model.requests.getFirst().tools()).extracting(t -> t.name())
                .contains("buscar_devedor", "situacao_casos", "minhas_pendencias").doesNotContain("status_importacao");

        // trilha de acesso como se tivesse aberto a tela
        assertThat(accessEvents("SUPPORT_DATA_QUERIED", "buscar_devedor")).isEqualTo(1);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SUPPORT_DATA_QUERIED' "
                + "and details ->> 'tool' = 'situacao_casos' and details ->> 'resourceIds' like ?",
                "%" + kase.id() + "%")).isEqualTo(1);
        // negócio: uma linha por pergunta, sem conteúdo
        assertThat(count("select count(*) from shared.business_event where event_type = 'helpdesk.question_answered' "
                + "and attributes ->> 'conversationId' = ? and attributes ->> 'topic' = 'DATA_LOOKUP'",
                first.conversationId().toString())).isEqualTo(2);
        assertThat(count("select count(*) from shared.business_event where event_type like 'helpdesk.%' "
                + "and (attributes::text like '%529.982%' or attributes::text like '%Devedor 529%')")).isZero();
        // conteúdo cifrado no banco
        assertThat(count("select count(*) from helpdesk.chat_message where conversation_id = ? "
                + "and content_encrypted like '%Devedor%'", first.conversationId())).isZero();
    }

    @Test
    void scopeAndPermissionAreEnforcedEvenWhenTheModelAsksForAToolNotOffered() {
        scenario("hd-scope");
        // operador restrito ao credor B não encontra o devedor do credor A
        AnswerView z = asOperator(operatorZ, () -> chat.ask(new AskCommand(null, null, "devedor " + CPF)));
        assertThat(z.answer().cards()).isEmpty();
        assertThat(count("select count(*) from shared.access_log where event_type = 'SUPPORT_DATA_QUERIED' "
                + "and actor_id = ? and details ->> 'results' = '0'", operatorZ)).isEqualTo(1);

        // perfil sem DEBTOR_VIEW: a ferramenta não é oferecida e, pedida mesmo assim, é negada no servidor
        model.requests.clear();
        AnswerView denied = asIntegration(() -> chat.ask(new AskCommand(null, null,
                "[sandbox:tool:buscar_devedor {\"texto\":\"Devedor\"}]")));
        assertThat(model.requests.getFirst().tools()).extracting(t -> t.name()).doesNotContain("buscar_devedor")
                .contains("registrar_pergunta", "minhas_pendencias");
        assertThat(denied.answer().cards()).isEmpty();
        assertThat(denied.answer().text()).contains("sem_permissao");
        assertThat(count("select count(*) from shared.access_log where event_type = 'ACCESS_DENIED' and actor_id = ? "
                + "and details ->> 'tool' = 'buscar_devedor' and details ->> 'via' = 'helpdesk'", integrationUser)).isEqualTo(1);
        assertThat(allModelInput()).doesNotContain(NAME);

        // referência de outra conversa não vale; conversa de outra pessoa é 404
        AnswerView x = asX(() -> chat.ask(new AskCommand(null, null, "devedor " + CPF)));
        AnswerView guess = asOperator(operatorY, () -> chat.ask(new AskCommand(null, null, "casos do D1")));
        assertThat(guess.answer().cards()).isEmpty();
        assertThat(guess.answer().text()).contains("referencia_desconhecida");
        assertThatThrownBy(() -> asOperator(operatorY, () -> chat.conversation(x.conversationId())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperator(operatorY, () -> chat.ask(new AskCommand(x.conversationId(), null, "oi"))))
                .isInstanceOf(ResourceNotFoundException.class);
        // tela que a pessoa não pode abrir não vira botão
        AnswerView screen = asIntegration(() -> chat.ask(new AskCommand(null, null, "abrir [tela:collection.queue]")));
        assertThat(screen.answer().actions()).isEmpty();
    }

    @Test
    void ticketIsMaskedOnTheServerConfirmedOnceAndDeduplicated() {
        scenario("hd-ticket");
        AnswerView lookup = asX(() -> chat.ask(new AskCommand(null, "collection.queue", "devedor " + CPF)));
        String complaint = "abrir chamado: a tela trava ao registrar a interação de Devedor 529, CPF " + CPF
                + ", acordo de R$ 1.234,56, fone (11) 98765-4321, Rua das Flores, 123";
        AnswerView drafted = asX(() -> chat.ask(new AskCommand(lookup.conversationId(), "collection.queue", complaint)));
        assertThat(drafted.answer().outcome()).isEqualTo("UNANSWERED");
        TicketView draft = drafted.answer().ticket();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.labels()).containsExactly("suporte");
        String email = system(() -> jdbcTemplate.queryForObject("select email from identity.app_user where id = ?",
                String.class, operatorX));
        assertThat(draft.body()).doesNotContain("529").doesNotContain("1.234,56").doesNotContain("98765")
                .doesNotContain("Flores").contains("[documento]").contains("[valor]")
                .contains(email).contains("O retorno será dado por e-mail").contains("Estação de cobrança")
                .contains("- Assessoria: ");
        assertThat(draft.title()).startsWith("[Suporte]");
        assertThat(tracker.created).isEmpty();

        TicketView opened = asX(() -> tickets.confirm(draft.id()));
        assertThat(opened.status()).isEqualTo("OPENED");
        long number = tracker.numbers.getFirst();
        assertThat(opened.issueNumber()).isEqualTo(number);
        assertThat(tracker.created).singleElement().satisfies(i -> {
            assertThat(i.body()).contains("<!-- gestor-cobranca:helpdesk-ticket:" + draft.id() + " -->")
                    .doesNotContain("529");
            assertThat(i.labels()).containsExactly("suporte");
        });
        // confirmar de novo devolve a mesma issue
        assertThat(asX(() -> tickets.confirm(draft.id())).issueNumber()).isEqualTo(number);
        assertThat(tracker.created).hasSize(1);
        // só o autor vê e confirma
        assertThatThrownBy(() -> asOperator(operatorY, () -> tickets.confirm(draft.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        // o mesmo texto de novo vira duplicado da issue aberta, sem abrir outra
        AnswerView again = asX(() -> chat.ask(new AskCommand(lookup.conversationId(), "collection.queue", complaint)));
        TicketView duplicate = asX(() -> tickets.confirm(again.answer().ticket().id()));
        assertThat(duplicate.status()).isEqualTo("DUPLICATE");
        assertThat(duplicate.issueNumber()).isEqualTo(number);
        assertThat(tracker.created).hasSize(1);

        assertThat(count("select count(*) from shared.access_log where event_type = 'SUPPORT_TICKET_OPENED' "
                + "and actor_id = ?", operatorX)).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'helpdesk.ticket_opened'"))
                .isEqualTo(2);
        assertThat(count("select count(*) from shared.audit_event where action = 'helpdesk.ticket_opened'")).isEqualTo(1);
        // o histórico traz o chamado com a situação atual
        assertThat(asX(() -> chat.conversation(lookup.conversationId())).messages())
                .anySatisfy(m -> assertThat(m.ticket() != null && "OPENED".equals(m.ticket().status())).isTrue());
    }

    @Test
    void privateRepositoryLockAndInterruptedCreationRecovery() {
        scenario("hd-lock");
        AnswerView a = asX(() -> chat.ask(new AskCommand(null, null, "abrir chamado: erro ao exportar o painel")));
        UUID ticketId = a.answer().ticket().id();
        tracker.requirePrivate = true;
        assertThatThrownBy(() -> asX(() -> tickets.confirm(ticketId)))
                .isInstanceOfSatisfying(HelpdeskExceptions.Unavailable.class,
                        e -> assertThat(e.code()).isEqualTo("helpdesk.ticket.repository_not_private"));
        assertThat(tracker.created).isEmpty();
        assertThat(asX(() -> tickets.get(ticketId)).status()).isEqualTo("FAILED");

        // repositório migrado para privado: a mesma confirmação passa
        tracker.privateRepository = true;
        assertThat(asX(() -> tickets.confirm(ticketId)).status()).isEqualTo("OPENED");

        // abertura que criou a issue mas falhou antes de gravar: a nova tentativa a reencontra pelo marcador
        AnswerView b = asX(() -> chat.ask(new AskCommand(null, null, "abrir chamado: botão de salvar some no celular")));
        tracker.failAfterCreate = true;
        assertThatThrownBy(() -> asX(() -> tickets.confirm(b.answer().ticket().id())))
                .isInstanceOf(HelpdeskExceptions.Unavailable.class);
        assertThat(tracker.created).hasSize(2);
        TicketView recovered = asX(() -> tickets.confirm(b.answer().ticket().id()));
        assertThat(recovered.status()).isEqualTo("OPENED");
        assertThat(recovered.issueNumber()).isEqualTo(tracker.numbers.get(1));
        assertThat(tracker.created).hasSize(2);
        assertThat(count("select count(*) from shared.business_event where event_type = 'helpdesk.ticket_opened' "
                + "and attributes ->> 'recovered' = 'true'")).isEqualTo(1);
        // cancelar só o que ainda não foi aberto
        assertThatThrownBy(() -> asX(() -> tickets.cancel(b.answer().ticket().id())))
                .isInstanceOf(br.com.gestorcobranca.shared.domain.ConflictException.class);
    }

    @Test
    void providerFailureOutOfScopeAndMaskedOutputDoNotBreakTheChat() {
        scenario("hd-fail");
        AnswerView failed = asX(() -> chat.ask(new AskCommand(null, null, "[sandbox:indisponivel] como registro?")));
        assertThat(failed.answer().outcome()).isEqualTo("FAILED");
        assertThat(failed.answer().text()).isEqualTo(HelpdeskChatService.FAILURE_TEXT);
        assertThat(count("select count(*) from shared.business_event where event_type = 'helpdesk.question_unanswered' "
                + "and attributes ->> 'errorCode' = 'MODEL_UNAVAILABLE'")).isEqualTo(1);

        AnswerView outside = asX(() -> chat.ask(new AskCommand(failed.conversationId(), null, "me passa uma receita de bolo")));
        assertThat(outside.answer().outcome()).isEqualTo("OUT_OF_SCOPE");

        AnswerView masked = asSupervisor(() -> chat.ask(new AskCommand(null, null,
                "status da importação [sandbox:valor-inventado]")));
        assertThat(masked.answer().text()).doesNotContain("529.982").doesNotContain("maria@exemplo.com")
                .contains("[documento]").contains("[e-mail]");
        assertThat(accessEvents("SUPPORT_DATA_QUERIED", "status_importacao")).isEqualTo(1);

        AnswerView pending = asX(() -> chat.ask(new AskCommand(null, null, "quais são minhas pendências?")));
        assertThat(pending.answer().outcome()).isEqualTo("ANSWERED");
        assertThat(accessEvents("SUPPORT_DATA_QUERIED", "minhas_pendencias")).isEqualTo(1);

        AnswerView tour = asX(() -> chat.ask(new AskCommand(null, "collection.queue", "quero fazer o tour")));
        assertThat(tour.answer().actions()).singleElement().satisfies(a -> {
            assertThat(a.kind()).isEqualTo("START_TOUR");
            assertThat(a.screen()).isEqualTo("collection.queue");
        });
        // chave de tela desconhecida é ignorada (o texto do manual nunca vem do cliente)
        AnswerView unknown = asX(() -> chat.ask(new AskCommand(null, "tela.inventada", "oi")));
        assertThat(unknown.answer().outcome()).isEqualTo("ANSWERED");
    }

    @Test
    void feedbackRetentionAndDatabaseImmutability() {
        scenario("hd-ret");
        AnswerView a = asX(() -> chat.ask(new AskCommand(null, "collection.queue", "como uso esta tela?")));
        assertThat(asX(() -> chat.feedback(a.answer().id(), new FeedbackCommand("UP", null))).feedback()).isEqualTo("UP");
        assertThat(asX(() -> chat.feedback(a.answer().id(), new FeedbackCommand("DOWN", "INCOMPLETE"))).feedback())
                .isEqualTo("DOWN");
        assertThatThrownBy(() -> asX(() -> chat.feedback(a.question().id(), new FeedbackCommand("UP", null))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> asOperator(operatorY, () -> chat.feedback(a.answer().id(), new FeedbackCommand("UP", null))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.business_event where event_type = 'helpdesk.feedback_recorded' "
                + "and attributes ->> 'rating' = 'DOWN' and attributes ->> 'screen' = 'collection.queue'")).isEqualTo(1);

        // o runtime não altera conteúdo nem metadados (só avaliação, chamado e descarte)
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update helpdesk.chat_message set content_encrypted = 'x' where id = ?", a.answer().id())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update helpdesk.chat_message set outcome = 'FAILED' where id = ?", a.answer().id())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "delete from helpdesk.chat_message where id = ?", a.answer().id())))
                .isInstanceOf(DataAccessException.class);

        // retenção: conteúdo descartado, metadados e avaliação ficam
        int purged = system(() -> chatStore.purgeBefore(Instant.now().plusSeconds(60), 100));
        assertThat(purged).isEqualTo(2);
        var view = asX(() -> chat.conversation(a.conversationId()));
        assertThat(view.messages()).allSatisfy(m -> {
            assertThat(m.purged()).isTrue();
            assertThat(m.text()).isNull();
        });
        assertThat(view.messages()).anySatisfy(m -> assertThat(m.feedback()).isEqualTo("DOWN"));
        // outra assessoria não vê nada (RLS)
        assertThat(count("select count(*) from helpdesk.chat_message")).isEqualTo(2);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var response = client.send(java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port + "/api/v1/helpdesk/status")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
