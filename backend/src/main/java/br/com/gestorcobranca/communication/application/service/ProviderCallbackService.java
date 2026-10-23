package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.ProviderCallbackUseCases;
import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.InboundMessage;
import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.StatusUpdate;
import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import br.com.gestorcobranca.communication.domain.model.OwnerType;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import br.com.gestorcobranca.communication.domain.service.OptOutKeywords;
import br.com.gestorcobranca.communication.domain.service.ProviderAddresses;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactFingerprint;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Mensagens recebidas e status de entrega informados pelo provedor (webhook ou sandbox), no tenant
 * da conta, como ator de sistema.
 * <p>
 * Mensagem recebida: idempotente pelo id do provedor; localiza a conversa aberta do contato (a de
 * saída mais recente), ou abre uma — com o devedor das conversas anteriores do contato, ou como
 * contato não identificado na fila; alinha o dono ao do caso (conversa sem dono vai para a fila);
 * abre a janela de 24 h; palavra de descadastro registra opt-out no compliance na mesma transação.
 * O registro da interação no caso é feito após o commit, pelo consumo de {@code MessageStatusChanged}.
 */
@Service
@Transactional
class ProviderCallbackService implements ProviderCallbackUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCallbackService.class);

    private final ChannelAccountRepository accounts;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final DebtorContactUseCases debtorContacts;
    private final ContactRestrictionUseCases restrictions;
    private final CaseReader cases;
    private final ConversationOwnership ownership;
    private final MessageRecorder recorder;
    private final CommunicationMetrics metrics;
    private final Clock clock;

    ProviderCallbackService(ChannelAccountRepository accounts, ConversationRepository conversations,
                            MessageRepository messages, DebtorContactUseCases debtorContacts,
                            ContactRestrictionUseCases restrictions, CaseReader cases, ConversationOwnership ownership,
                            MessageRecorder recorder, CommunicationMetrics metrics, Clock clock) {
        this.accounts = accounts;
        this.conversations = conversations;
        this.messages = messages;
        this.debtorContacts = debtorContacts;
        this.restrictions = restrictions;
        this.cases = cases;
        this.ownership = ownership;
        this.recorder = recorder;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public boolean inbound(UUID channelAccountId, InboundMessage in) {
        ChannelAccount account = accounts.findById(channelAccountId)
                .orElseThrow(() -> ResourceNotFoundException.of("channel_account", channelAccountId));
        String provider = account.getProvider().name();
        if (messages.findByChannelAndProviderMessageId(account.getChannel(), in.providerMessageId()).isPresent()) {
            metrics.webhookEvent(provider, "message", "duplicate");
            return false;
        }
        Optional<String> address = normalize(account.getChannel(), in.from());
        Optional<ContactFingerprint> fingerprint = address.flatMap(a ->
                debtorContacts.tryFingerprint(account.getChannel() == ChannelType.EMAIL ? "EMAIL" : "PHONE", a));
        if (fingerprint.isEmpty()) {
            LOG.atWarn().addKeyValue("provider", provider).addKeyValue("accountId", channelAccountId)
                    .log("Mensagem recebida de endereço fora do padrão; ignorada");
            metrics.webhookEvent(provider, "message", "ignored");
            return false;
        }
        Instant at = in.timestamp() == null ? Instant.now(clock) : in.timestamp();
        Conversation conversation = conversationFor(account, address.get(), fingerprint.get());
        boolean reply = conversation.getLastOutboundAt() != null;

        Optional<CaseView> kase = ownership.activeCase(conversation);
        if (kase.isPresent()) {
            conversation = ownership.syncFromCase(conversation, kase.get(), true);
        } else if (conversation.getOwnerType() == OwnerType.NONE) {
            conversation = ownership.awaitHuman(conversation);
        }
        conversation.registerInbound(at);
        Conversation saved = conversations.save(conversation);
        Message message = messages.saveAndFlush(Message.inbound(saved, in.providerMessageId(), in.text(), at));

        Optional<String> optOut = OptOutKeywords.match(in.text());
        optOut.ifPresent(keyword -> {
            restrictions.registerOptOutFromChannel(saved.getDebtorId(), account.getChannel().name(), address.get(),
                    keyword);
            metrics.optOut(account.getChannel().name());
        });
        metrics.webhookEvent(provider, "message", "applied");
        recorder.received(message, saved, reply, in.type() == null ? "unknown" : in.type().toLowerCase(Locale.ROOT),
                optOut);
        return true;
    }

    @Override
    public boolean status(UUID channelAccountId, StatusUpdate update) {
        ChannelAccount account = accounts.findById(channelAccountId)
                .orElseThrow(() -> ResourceNotFoundException.of("channel_account", channelAccountId));
        String provider = account.getProvider().name();
        MessageStatus reported;
        try {
            reported = MessageStatus.valueOf(update.status().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            metrics.webhookEvent(provider, "status", "ignored");
            return false;
        }
        if (reported != MessageStatus.DELIVERED && reported != MessageStatus.READ && reported != MessageStatus.FAILED) {
            metrics.webhookEvent(provider, "status", "ignored");
            return false;
        }
        Optional<Message> found = messages.findByChannelAndProviderMessageId(account.getChannel(), update.providerMessageId());
        if (found.isEmpty()) {
            metrics.webhookEvent(provider, "status", "unknown_message");
            return false;
        }
        Message message = found.get();
        Instant at = update.timestamp() == null ? Instant.now(clock) : update.timestamp();
        String code = update.errorCode() == null ? null : provider + "_" + update.errorCode();
        if (!message.applyProviderStatus(reported, code, update.errorDetail(), at)) {
            metrics.webhookEvent(provider, "status", "duplicate");
            return false;
        }
        messages.save(message);
        Conversation conversation = conversations.findById(message.getConversationId()).orElseThrow();
        metrics.webhookEvent(provider, "status", "applied");
        recorder.providerStatus(message, conversation);
        return true;
    }

    private static Optional<String> normalize(ChannelType channel, String from) {
        return switch (channel) {
            case WHATSAPP, SMS -> from != null && from.startsWith("+") ? ProviderAddresses.fromWhatsApp(from.substring(1))
                    : ProviderAddresses.fromWhatsApp(from);
            case EMAIL -> ProviderAddresses.email(from);
        };
    }

    /**
     * Conversa aberta do contato na conta (a de saída mais recente); senão uma nova, com o devedor e
     * credor da conversa anterior do contato (e o caso ativo, se único) ou não identificada.
     */
    private Conversation conversationFor(ChannelAccount account, String address, ContactFingerprint fingerprint) {
        List<Conversation> open = conversations.findOpenByContact(account.getId(), fingerprint.hash());
        if (!open.isEmpty()) {
            return conversations.findByIdForUpdate(open.getFirst().getId()).orElseThrow();
        }
        Instant now = Instant.now(clock);
        Conversation.Contact contact = new Conversation.Contact(address, fingerprint.hash(), fingerprint.maskedValue());
        Optional<Conversation> previous = conversations.findIdentifiedByContact(account.getId(), fingerprint.hash()).stream()
                .filter(c -> account.getCreditorId() == null || account.getCreditorId().equals(c.getCreditorId()))
                .findFirst();
        if (previous.isPresent()) {
            Conversation p = previous.get();
            Optional<CaseView> kase = cases.singleActive(p.getDebtorId(), p.getCreditorId());
            Conversation.Owner owner = kase.map(k -> ConversationOwnership.fromCase(k, (String) null, true))
                    .orElse(Conversation.Owner.queue(null, null));
            return conversations.saveAndFlush(new Conversation(p.getDebtorId(), p.getCreditorId(),
                    kase.map(CaseView::portfolioId).orElse(null), kase.map(CaseView::id).orElse(null), account.getChannel(),
                    account.getId(), p.getContactId(), contact, owner, now));
        }
        return conversations.saveAndFlush(new Conversation(null, account.getCreditorId(), null, null, account.getChannel(),
                account.getId(), null, contact, Conversation.Owner.queue(null, null), now));
    }
}
