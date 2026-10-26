package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.ActionView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.CardView;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases.TicketView;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase.Screen;
import br.com.gestorcobranca.helpdesk.domain.model.QuestionTopic;
import br.com.gestorcobranca.helpdesk.domain.service.PersonalData.Placeholders;
import br.com.gestorcobranca.helpdesk.domain.service.ReferenceBook;
import br.com.gestorcobranca.shared.security.Actor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Estado de uma pergunta em andamento: quem pergunta, a tela, as referências da conversa, os marcadores de
 * documento e o que as ferramentas produziram para a resposta (cartões, ações, rascunho de chamado). Só vive na
 * requisição; o que precisa durar é gravado com a resposta.
 */
final class TurnState {

    final Actor actor;
    final UUID conversationId;
    final Screen screen;
    final ReferenceBook references;
    final Placeholders placeholders;
    final UserProfile profile;
    /** Nomes de devedores exibidos nesta conversa (cartões): mascarados no texto do chamado. */
    final Set<String> shownNames = new LinkedHashSet<>();
    final List<CardView> cards = new ArrayList<>();
    final List<ActionView> actions = new ArrayList<>();
    String lastUserText;
    TicketView ticket;
    QuestionTopic topic;
    Boolean resolved;
    int toolCalls;

    TurnState(Actor actor, UUID conversationId, Screen screen, ReferenceBook references, Placeholders placeholders,
              UserProfile profile) {
        this.actor = actor;
        this.conversationId = conversationId;
        this.screen = screen;
        this.references = references;
        this.placeholders = placeholders;
        this.profile = profile;
    }

    void card(CardView card) {
        if (cards.stream().noneMatch(c -> c.ref() != null && c.ref().equals(card.ref()) && c.kind().equals(card.kind()))
                && cards.size() < 30) {
            cards.add(card);
        }
    }

    void action(ActionView action) {
        if (actions.stream().noneMatch(a -> a.kind().equals(action.kind()) && a.screen().equals(action.screen()))
                && actions.size() < 6) {
            actions.add(action);
        }
    }

    /**
     * Quem pergunta, na linguagem do manual.
     *
     * @param roles       códigos dos perfis
     * @param roleLabels  rótulos dos perfis padrão (perfis próprios da assessoria aparecem pelo código)
     * @param permissions permissões efetivas (códigos)
     */
    record UserProfile(String name, String email, String tenantName, Set<String> roles, List<String> roleLabels,
                       Set<String> permissions, boolean allCreditors) {
    }
}
