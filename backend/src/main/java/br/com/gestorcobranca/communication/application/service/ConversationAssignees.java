package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Regra única de quem pode receber uma conversa, usada pela lista ({@link ConversationAssigneeService}) e pela
 * gravação ({@link ConversationOwnership#assign}).
 */
@Component
class ConversationAssignees {

    private final UserDirectory users;

    ConversationAssignees(UserDirectory users) {
        this.users = users;
    }

    /**
     * Ativo, com {@code INBOX_VIEW} e {@code MESSAGE_SEND} (vê a conversa e responde); com caso ativo, a regra do
     * collection para trabalhar o caso ({@link CaseAssigneeUseCases#isCaseWorker}); sem caso, acesso ao credor da
     * conversa — sem credor (contato não identificado), todos os credores, como exige {@code ConversationAccess}.
     *
     * @param creditorId credor do caso ativo ou, sem caso, o da conversa (pode ser nulo)
     */
    static boolean isEligible(UserSummary user, UUID creditorId, boolean withCase) {
        if (user == null || !user.active() || !user.permissions().contains(Permission.INBOX_VIEW.name())
                || !user.permissions().contains(Permission.MESSAGE_SEND.name())) {
            return false;
        }
        if (withCase) {
            return CaseAssigneeUseCases.isCaseWorker(user, creditorId);
        }
        return creditorId == null ? user.allCreditors() : user.canAccessCreditor(creditorId);
    }

    /** Candidatos: usuários ativos que respondem conversas (o filtro final é {@link #isEligible}). */
    Collection<UserSummary> candidates() {
        return users.activeUsersWithPermission(Permission.MESSAGE_SEND.name());
    }

    void requireEligible(UUID userId, UUID creditorId, boolean withCase) {
        UserSummary user = users.find(userId).orElseThrow(() -> new BusinessRuleException("conversation.assignee_invalid",
                "Operador inexistente"));
        if (!isEligible(user, creditorId, withCase)) {
            throw new BusinessRuleException("conversation.assignee_invalid", withCase
                    ? "O operador precisa estar ativo, ver a caixa de entrada, responder mensagens e trabalhar casos do credor"
                    : "O operador precisa estar ativo, ver a caixa de entrada, responder mensagens e ter acesso ao credor "
                            + "da conversa");
        }
    }
}
