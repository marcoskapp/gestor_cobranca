package br.com.gestorcobranca.communication;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.ConversationAssigneeUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationAssigneeUseCases.AssigneeView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.InboxFilter;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.RoleCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Atribuição de conversa: a lista de operadores mostra exatamente quem a atribuição aceita — com caso, quem vê a caixa
 * de entrada, responde e trabalha o caso do credor; sem caso, quem vê e responde com acesso ao credor da conversa.
 */
class ConversationAssigneeIT extends CommunicationTestSupport {

    @Autowired
    ConversationAssigneeUseCases assignees;

    UUID debtor;
    CaseView kase;
    UUID phoneOnly;

    @BeforeEach
    void setUp() {
        provision("convassign");
        debtor = debtor("529.982.247-25");
        kase = loadCase(creditorA, portfolioA, debtor, "500.00");
        publishedTemplate(null, "COBRANCA", "WHATSAPP");
        // operador só de telefone: trabalha casos, mas não vê a caixa de entrada nem responde mensagens
        UUID role = asAdmin(() -> roles.create(new RoleCommand("SO_TELEFONE", "Só telefone", null,
                Set.of("CASE_VIEW", "CASE_WORK")))).id();
        phoneOnly = system(() -> userManagement.create(new CreateUserCommand("Operador Telefone", "tel-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null, Set.of(role), true, Set.of())).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", phoneOnly));
    }

    private List<UUID> list(UUID conversationId) {
        return asSupervisor(() -> assignees.search(conversationId, null, null, new PageQuery(0, 50))).content().stream()
                .map(AssigneeView::id).toList();
    }

    @Test
    void conversationWithCaseListsWhoAnswersAndWorksTheCase() {
        UUID conversationId = asOperator(operatorX, () -> messages.send(new SendMessageCommand(null, debtor, creditorA,
                null, "WHATSAPP", null, "COBRANCA", Map.of("nome", "Maria", "valor", "500.00"), null, null, null)))
                .conversationId();
        List<UUID> eligible = list(conversationId);
        assertThat(eligible).contains(operatorX, operatorY, supervisor).doesNotContain(operatorZ, phoneOnly);

        // fora da lista → recusado ao gravar (nem o caso muda)
        for (UUID refused : List.of(operatorZ, phoneOnly)) {
            assertThatThrownBy(() -> asSupervisor(() -> conversations.assign(conversationId, refused, null)))
                    .hasFieldOrPropertyWithValue("code", "conversation.assignee_invalid");
        }
        ConversationView assigned = asSupervisor(() -> conversations.assign(conversationId, operatorY, "Carteira"));
        assertThat(assigned.ownerUserId()).isEqualTo(operatorY);
        assertThat(system(() -> cases.get(kase.id())).assigneeUserId()).isEqualTo(operatorY);

        // busca por nome e resolução do escolhido
        assertThat(asSupervisor(() -> assignees.search(conversationId, "OPERADOR Y", null, new PageQuery(0, 20)))
                .content()).extracting(AssigneeView::id).containsExactly(operatorY);
        assertThat(asSupervisor(() -> assignees.search(conversationId, null, Set.of(operatorY, operatorZ),
                new PageQuery(0, 20))).content()).extracting(AssigneeView::id).containsExactly(operatorY);

        // sem INBOX_MANAGE: recusado
        assertThatThrownBy(() -> asOperator(operatorX, () -> assignees.search(conversationId, null, null,
                new PageQuery(0, 20)))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and actor_id = ? and details ->> 'resource' = 'conversation_assignee' and details ->> 'withCase' = 'true' "
                + "and details ->> 'conversationId' = ?", supervisor, conversationId.toString())).isGreaterThanOrEqualTo(3);
    }

    @Test
    void conversationWithoutCaseFollowsTheCreditorOfTheConversation() {
        asAdmin(() -> {
            sandbox.simulateInbound(whatsapp.id(), "5511912345678", "Recebi uma cobrança, quem é?");
            return null;
        });
        ConversationView unknown = asSupervisor(() -> conversations.inbox(new InboxFilter(null, null, null, null, null,
                "OPEN", Set.of("QUEUE"), null, null, null, true), PageQuery.of(0, 20))).content().stream()
                .filter(c -> c.debtorId() == null).findFirst().orElseThrow();
        assertThat(unknown.caseId()).isNull();
        assertThat(unknown.creditorId()).isNull();

        // sem credor: só quem tem todos os credores; CASE_WORK não é exigido, mas responder sim
        List<UUID> eligible = list(unknown.id());
        assertThat(eligible).contains(operatorX, operatorY, supervisor).doesNotContain(operatorZ, phoneOnly);
        assertThatThrownBy(() -> asSupervisor(() -> conversations.assign(unknown.id(), operatorZ, null)))
                .hasFieldOrPropertyWithValue("code", "conversation.assignee_invalid");
        ConversationView assigned = asSupervisor(() -> conversations.assign(unknown.id(), operatorX, null));
        assertThat(assigned.ownerType()).isEqualTo("USER");
        assertThat(assigned.ownerUserId()).isEqualTo(operatorX);
    }
}
