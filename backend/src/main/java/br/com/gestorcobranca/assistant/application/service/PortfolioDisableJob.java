package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.HandoffCommand;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.InboxFilter;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Desligamento imediato de carteira: as conversas abertas que o assistente conduz na carteira vão para
 * humanos (motivo {@code ASSISTANT_DISABLED}). Cada conversa em transação própria; idempotente (as já
 * transferidas não aparecem mais com dono {@code ASSISTANT}).
 */
@Component
class PortfolioDisableJob implements JobHandler<PortfolioDisableJob.Payload> {

    static final String TYPE = "assistant.portfolio_disable";
    private static final Logger LOG = LoggerFactory.getLogger(PortfolioDisableJob.class);

    record Payload(UUID portfolioId, String reason) {
    }

    private final ConversationUseCases conversations;
    private final AssistantActors actors;

    PortfolioDisableJob(ConversationUseCases conversations, AssistantActors actors) {
        this.conversations = conversations;
        this.actors = actors;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        InboxFilter filter = new InboxFilter(null, payload.portfolioId(), null, null, null, "OPEN", Set.of("ASSISTANT"),
                null, null, null, null);
        for (int guard = 0; guard < 1000; guard++) {
            List<ConversationView> page = conversations.inbox(filter, PageQuery.of(0, 100)).content();
            if (page.isEmpty()) {
                return;
            }
            int moved = 0;
            for (ConversationView c : page) {
                try {
                    actors.system(context.tenantId(), () -> conversations.handoffToHumans(c.id(), new HandoffCommand(
                            HandoffReason.ASSISTANT_DISABLED.name(),
                            "Assistente desligado na carteira: " + payload.reason(), null, "portfolio",
                            payload.portfolioId())));
                    moved++;
                } catch (DomainException e) {
                    LOG.atWarn().addKeyValue("conversationId", c.id()).addKeyValue("code", e.code())
                            .log("Conversa não transferida no desligamento da carteira");
                }
            }
            if (moved == 0) {
                return;
            }
        }
    }
}
