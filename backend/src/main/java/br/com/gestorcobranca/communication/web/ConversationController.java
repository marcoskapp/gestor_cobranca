package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationDetail;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.DraftView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.HandoffCommand;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.HandoffView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.InboxFilter;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Caixa de entrada (F12): conversas, dono do atendimento, transferências e rascunhos do assistente. */
@RestController
@RequestMapping(ApiPaths.V1 + "/communication/conversations")
class ConversationController {

    private final ConversationUseCases conversations;
    private final MessageUseCases messages;
    private final CurrentActor currentActor;

    ConversationController(ConversationUseCases conversations, MessageUseCases messages, CurrentActor currentActor) {
        this.conversations = conversations;
        this.messages = messages;
        this.currentActor = currentActor;
    }

    /** @param assignee {@code me} ou id do operador dono */
    @GetMapping
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    PageResponse<ConversationView> inbox(@RequestParam(required = false) UUID creditorId,
                                         @RequestParam(required = false) UUID portfolioId,
                                         @RequestParam(required = false) UUID debtorId,
                                         @RequestParam(required = false) UUID caseId,
                                         @RequestParam(required = false) String channel,
                                         @RequestParam(required = false, defaultValue = "OPEN") String status,
                                         @RequestParam(required = false) Set<String> owner,
                                         @RequestParam(required = false) String assignee,
                                         @RequestParam(required = false) UUID teamId,
                                         @RequestParam(required = false) String requiredProfile,
                                         @RequestParam(required = false) Boolean awaitingReply,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        UUID ownerUserId = assignee == null || assignee.isBlank() ? null
                : "me".equalsIgnoreCase(assignee) ? currentActor.actorId() : parseId(assignee);
        return conversations.inbox(new InboxFilter(creditorId, portfolioId, debtorId, caseId, channel, status, owner,
                ownerUserId, teamId, requiredProfile, awaitingReply), PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    ConversationDetail get(@PathVariable UUID id) {
        return conversations.get(id);
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    PageResponse<MessageView> messages(@PathVariable UUID id, @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return messages.listByConversation(id, PageQuery.of(page, size));
    }

    @GetMapping("/{id}/handoffs")
    @PreAuthorize("hasAuthority('INBOX_VIEW')")
    List<HandoffView> handoffs(@PathVariable UUID id) {
        return conversations.handoffs(id);
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    ConversationView read(@PathVariable UUID id) {
        return conversations.markRead(id);
    }

    @PostMapping("/{id}/identify")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    ConversationView identify(@PathVariable UUID id, @Valid @RequestBody IdentifyRequest request) {
        return conversations.identify(id, request.debtorId(), request.creditorId());
    }

    @PostMapping("/{id}/take-over")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    ConversationView takeOver(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return conversations.takeOver(id, reason(request));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    ConversationView release(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return conversations.release(id, reason(request));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('INBOX_MANAGE')")
    ConversationView assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return conversations.assign(id, request.userId(), request.reason());
    }

    @PostMapping("/{id}/assistant")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    ConversationView returnToAssistant(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return conversations.returnToAssistant(id, reason(request));
    }

    @PostMapping("/{id}/handoff")
    @PreAuthorize("hasAuthority('INBOX_MANAGE')")
    ConversationView handoff(@PathVariable UUID id, @Valid @RequestBody HandoffRequest request) {
        return conversations.handoffToHumans(id, new HandoffCommand(request.reasonCode(), request.summary(),
                request.requiredProfile(), null, null));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    ConversationView close(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return conversations.close(id, reason(request));
    }

    @PostMapping("/{id}/drafts/{draftId}/send")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    MessageView sendDraft(@PathVariable UUID id, @PathVariable UUID draftId,
                          @Valid @RequestBody(required = false) DraftSendRequest request) {
        return conversations.sendDraft(id, draftId, request == null ? null : request.content());
    }

    @PostMapping("/{id}/drafts/{draftId}/discard")
    @PreAuthorize("hasAuthority('MESSAGE_SEND')")
    DraftView discardDraft(@PathVariable UUID id, @PathVariable UUID draftId) {
        return conversations.discardDraft(id, draftId);
    }

    private static String reason(ReasonRequest request) {
        return request == null ? null : request.reason();
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("conversation.assignee_invalid", "Use 'me' ou o id do operador");
        }
    }

    record ReasonRequest(@Size(max = 300) String reason) {
    }

    record IdentifyRequest(@NotNull UUID debtorId, UUID creditorId) {
    }

    record AssignRequest(@NotNull UUID userId, @Size(max = 300) String reason) {
    }

    record HandoffRequest(@NotBlank @Size(max = 60) String reasonCode, @NotBlank @Size(max = 2000) String summary,
                          @Size(max = 40) String requiredProfile) {
    }

    record DraftSendRequest(@Size(max = 4096) String content) {
    }
}
