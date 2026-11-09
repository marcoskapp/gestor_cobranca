package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.DraftView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.HandoffCommand;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases;
import br.com.gestorcobranca.compliance.application.port.in.VulnerabilityUseCases.SuggestCommand;
import br.com.gestorcobranca.shared.domain.DomainException;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Saídas do turno para o communication e o compliance, sempre como o ator {@code ASSISTANT} do turno:
 * resposta ({@code MessageUseCases#send} — o porteiro de contato decide no pedido e de novo no envio),
 * transferência ({@code ConversationUseCases#handoffToHumans}), rascunho ({@code proposeDraft}) e
 * opt-out pedido na conversa. Sem política vigente, a transferência é feita como sistema.
 * <p>
 * Transferência por {@code VULNERABILITY}: além de ir para o operador sênior, deixa uma <b>sugestão</b> de marcador de
 * vulnerabilidade no compliance (só a referência da conversa, sem texto nem categoria presumida); uma pessoa confirma
 * a categoria ou dispensa.
 */
@Component
class AssistantChannel {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantChannel.class);

    private final MessageUseCases messages;
    private final ConversationUseCases conversations;
    private final ContactRestrictionUseCases restrictions;
    private final AssistantActors actors;
    private final VulnerabilityUseCases vulnerability;
    private final AssistantMetrics metrics;

    AssistantChannel(MessageUseCases messages, ConversationUseCases conversations, ContactRestrictionUseCases restrictions,
                     AssistantActors actors, VulnerabilityUseCases vulnerability, AssistantMetrics metrics) {
        this.messages = messages;
        this.conversations = conversations;
        this.restrictions = restrictions;
        this.actors = actors;
        this.vulnerability = vulnerability;
        this.metrics = metrics;
    }

    /**
     * Envia texto ao devedor (com a apresentação de assistente virtual na primeira mensagem da etapa).
     *
     * @return a mensagem ({@code FAILED/CONTACT_DENIED} quando o porteiro negou) ou vazio se a conversa
     *         já não está com o assistente
     */
    Optional<MessageView> send(TurnContext ctx, String text) {
        String body = text;
        if (!ctx.session.isIntroduced()) {
            body = ctx.policy.settings().identificationMessage() + "\n\n" + text;
        }
        String content = body;
        try {
            MessageView view = actors.as(ctx.actor, () -> messages.send(new SendMessageCommand(ctx.conversation.id(), null,
                    null, null, null, null, null, null, content, null, ctx.nextSendKey())));
            if (!"FAILED".equals(view.status())) {
                ctx.session.introduce();
            }
            return Optional.of(view);
        } catch (DomainException e) {
            if ("conversation.not_with_assistant".equals(e.code())) {
                LOG.atInfo().addKeyValue("conversationId", ctx.conversation.id())
                        .log("Conversa deixou de estar com o assistente antes do envio");
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Transferência para humanos; já transferida (corrida com outro pedido) não é erro. */
    void handoff(TurnContext ctx, HandoffReason reason, String summary, String referenceType, UUID referenceId) {
        if (reason == HandoffReason.VULNERABILITY) {
            suggestVulnerability(ctx);
        }
        String profile = ctx.hasPolicy() ? ctx.policy.settings().requiredProfileFor(reason) : reason.defaultProfile();
        HandoffCommand command = new HandoffCommand(reason.name(), summary, profile, referenceType, referenceId);
        try {
            if (ctx.actor != null) {
                actors.as(ctx.actor, () -> conversations.handoffToHumans(ctx.conversation.id(), command));
            } else {
                actors.system(ctx.tenantId, () -> conversations.handoffToHumans(ctx.conversation.id(), command));
            }
        } catch (DomainException e) {
            if ("conversation.not_with_assistant".equals(e.code()) || "conversation.closed".equals(e.code())) {
                LOG.atInfo().addKeyValue("conversationId", ctx.conversation.id()).addKeyValue("code", e.code())
                        .log("Transferência dispensada: a conversa já não está com o assistente");
                return;
            }
            throw e;
        }
    }

    /**
     * Sugestão de marcador (gatilho {@code VULNERABILITY}); falha não impede a transferência — só log técnico, sem
     * dado pessoal.
     */
    void suggestVulnerability(TurnContext ctx) {
        if (ctx.conversation.debtorId() == null) {
            return;
        }
        try {
            boolean created = actors.system(ctx.tenantId, () -> vulnerability.suggest(new SuggestCommand(
                    ctx.conversation.debtorId(), ctx.conversation.creditorId(), "ASSISTANT", "assistant_conversation",
                    ctx.conversation.id()))).isPresent();
            metrics.vulnerabilitySuggestion(created ? "created" : "ignored");
        } catch (DataIntegrityViolationException | DomainException e) {
            // corrida com outra detecção do mesmo devedor (índice único) ou regra de negócio: esperado, sem alarme
            metrics.vulnerabilitySuggestion("rejected");
            LOG.atInfo().addKeyValue("conversationId", ctx.conversation.id())
                    .addKeyValue("error", e.getClass().getSimpleName())
                    .log("Sugestão de marcador de vulnerabilidade não registrada; a transferência segue");
        } catch (RuntimeException e) {
            // falha inesperada: métrica para alarme e log de erro; a transferência ao humano não pode ser perdida
            metrics.vulnerabilitySuggestion("error");
            LOG.atError().addKeyValue("conversationId", ctx.conversation.id())
                    .addKeyValue("error", e.getClass().getSimpleName())
                    .log("Falha inesperada ao sugerir marcador de vulnerabilidade; a transferência segue");
        }
    }

    DraftView draft(TurnContext ctx, String content, String rationale) {
        return actors.as(ctx.actor, () -> conversations.proposeDraft(ctx.conversation.id(),
                new ConversationUseCases.ProposeDraftCommand(ctx.mode.name(), content, rationale)));
    }

    /**
     * Opt-out pedido pelo devedor na conversa: canal da conversa, credor da conversa (escopo do ator),
     * origem DEBTOR. O descadastro por palavra exata ({@code SAIR}) já é feito pelo communication.
     */
    UUID optOut(TurnContext ctx) {
        return actors.as(ctx.actor, () -> restrictions.create(new CreateRestrictionCommand(ctx.conversation.debtorId(),
                ctx.conversation.creditorId(), ctx.conversation.channel(), "OPT_OUT", null, null,
                "Pedido do devedor em conversa com o assistente virtual", "DEBTOR", null, null)).id());
    }
}
