package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.spi.DisputeDebtorMessenger;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ChannelContact;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementa a porta do collection para a resposta da contestação ao devedor (ADR-0010, {@code collection :: spi}):
 * mensagem transacional por e-mail ou WhatsApp, com o porteiro de contato na categoria {@code TRANSACTIONAL}. A
 * mensagem segue o fluxo normal (fila de envio, conversa, linha do tempo e interação no caso).
 */
@Component
@Transactional
class DisputeNoticeMessenger implements DisputeDebtorMessenger {

    private static final List<ChannelType> CHANNELS = List.of(ChannelType.EMAIL, ChannelType.WHATSAPP);

    private final MessageService messageService;
    private final MessageRepository messages;
    private final ChannelAccountRepository accounts;
    private final DebtorContactUseCases debtorContacts;
    private final ContactGate gate;
    private final AccessScope accessScope;
    private final ConversationRepository conversations;
    private final Clock clock;

    DisputeNoticeMessenger(MessageService messageService, MessageRepository messages, ChannelAccountRepository accounts,
                           DebtorContactUseCases debtorContacts, ContactGate gate, AccessScope accessScope,
                           ConversationRepository conversations, Clock clock) {
        this.messageService = messageService;
        this.messages = messages;
        this.accounts = accounts;
        this.debtorContacts = debtorContacts;
        this.gate = gate;
        this.accessScope = accessScope;
        this.conversations = conversations;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChannelCheck> check(UUID debtorId, UUID creditorId) {
        accessScope.requireCreditor(creditorId);
        List<ChannelCheck> out = new ArrayList<>();
        for (ChannelType channel : CHANNELS) {
            boolean configured = accounts.findActiveForCreditor(channel, creditorId)
                    .or(() -> accounts.findActiveDefault(channel)).isPresent();
            Optional<ChannelContact> contact = debtorContacts.contactsForChannel(debtorId,
                            channel == ChannelType.EMAIL ? "EMAIL" : "PHONE").stream()
                    .filter(c -> channel != ChannelType.WHATSAPP || c.whatsapp())
                    .findFirst();
            if (!configured || contact.isEmpty()) {
                out.add(new ChannelCheck(channel.name(), false,
                        List.of(!configured ? "CHANNEL_NOT_CONFIGURED" : "NO_CONTACT"), null,
                        contact.map(ChannelContact::contactId).orElse(null), null));
                continue;
            }
            String masked = debtorContacts.fingerprint(contact.get().type(), contact.get().value()).maskedValue();
            ContactDecision decision = gate.evaluate(new ContactRequest(debtorId, creditorId, channel.name(),
                    contact.get().value(), null, "OPERATOR", null, null, Message.CATEGORY_TRANSACTIONAL));
            List<String> reasons = new ArrayList<>(decision.reasons().stream().map(Enum::name).toList());
            boolean windowOpen = channel != ChannelType.WHATSAPP || whatsappWindowOpen(creditorId, contact.get());
            if (!windowOpen) {
                // texto livre no WhatsApp só dentro da janela de 24 h desde a última mensagem do devedor
                reasons.add("WHATSAPP_WINDOW_CLOSED");
            }
            out.add(new ChannelCheck(channel.name(), decision.allowed() && windowOpen, reasons, decision.nextAllowedAt(),
                    contact.get().contactId(), masked));
        }
        return out;
    }

    private boolean whatsappWindowOpen(UUID creditorId, ChannelContact contact) {
        Instant now = Instant.now(clock);
        String hash = debtorContacts.fingerprint(contact.type(), contact.value()).hash();
        return accounts.findActiveForCreditor(ChannelType.WHATSAPP, creditorId)
                .or(() -> accounts.findActiveDefault(ChannelType.WHATSAPP))
                .flatMap(a -> conversations.findOpen(a.getId(), hash, creditorId))
                .filter(c -> c.sessionWindowOpen(now)).isPresent();
    }

    @Override
    public Sent send(Notice notice) {
        Message m = messageService.queueTransactional(notice.debtorId(), notice.creditorId(), notice.caseId(),
                notice.channel(), notice.contactId(), notice.subject(), notice.body(), "dispute", notice.disputeId());
        return new Sent(m.getId(), m.getStatus().name(), m.getFailureCode(), m.getFailureDetail());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Status> status(UUID messageId) {
        return messages.findById(messageId).map(m -> new Status(m.getId(), m.getStatus().name(), m.getFailureCode(),
                m.getFailureDetail(), m.getStatusAt()));
    }
}
