package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Conversa com um contato do devedor num canal (conta de canal + contato), no contexto de um
 * credor e, quando identificado, de um caso de cobrança. Há no máximo uma conversa aberta por
 * (conta, contato, credor).
 * <p>
 * <b>Dono do atendimento.</b> Com caso, o dono de referência é o do caso (collection): os comandos
 * de dono do módulo são aplicados primeiro no caso e depois aqui; mudanças feitas diretamente no
 * caso são alinhadas por evento e na chegada de mensagens. Sem caso (contato não identificado ou
 * apenas notificações), o dono é desta conversa.
 * <p>
 * <b>Janela do WhatsApp.</b> Mensagem livre só até 24 h após a última mensagem do devedor; fora
 * dela, apenas template aprovado.
 */
@Entity
@Table(name = "conversation", schema = "communication")
public class Conversation extends TenantScopedEntity {

    public static final Duration WHATSAPP_SESSION_WINDOW = Duration.ofHours(24);

    @Column(name = "debtor_id")
    private UUID debtorId;

    @Column(name = "creditor_id")
    private UUID creditorId;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "case_id")
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private ChannelType channel;

    @Column(name = "channel_account_id", nullable = false, updatable = false)
    private UUID channelAccountId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "contact_hash", nullable = false, length = 64, updatable = false)
    private String contactHash;

    @Column(name = "contact_masked", nullable = false, length = 254, updatable = false)
    private String contactMasked;

    /** Contato normalizado (E.164 ou e-mail) usado no envio; criptografado. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "contact_encrypted", nullable = false, length = 1000, updatable = false)
    private String contactValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConversationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "owner_team_id")
    private UUID ownerTeamId;

    @Column(name = "required_profile", length = 40)
    private String requiredProfile;

    @Column(name = "owner_since", nullable = false)
    private Instant ownerSince;

    @Column(name = "last_inbound_at")
    private Instant lastInboundAt;

    @Column(name = "last_outbound_at")
    private Instant lastOutboundAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    /** Primeira mensagem do devedor ainda sem resposta (SLA de primeira resposta). */
    @Column(name = "awaiting_since")
    private Instant awaitingSince;

    @Column(name = "unread_count", nullable = false)
    private int unreadCount;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "close_reason", length = 300)
    private String closeReason;

    protected Conversation() {
    }

    /**
     * @param debtorId     nulo = contato ainda não identificado (mensagem recebida de número desconhecido)
     */
    public Conversation(UUID debtorId, UUID creditorId, UUID portfolioId, UUID caseId, ChannelType channel,
                        UUID channelAccountId, UUID contactId, Contact contact, Owner owner, Instant now) {
        Guard.require(channel != null, "conversation.channel_required", "Canal obrigatório");
        Guard.require(channelAccountId != null, "conversation.account_required", "Conta de canal obrigatória");
        Guard.require(contact != null, "conversation.contact_required", "Contato obrigatório");
        Guard.require(caseId == null || (debtorId != null && creditorId != null), "conversation.case_without_debtor",
                "Conversa de caso exige devedor e credor");
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.caseId = caseId;
        this.channel = channel;
        this.channelAccountId = channelAccountId;
        this.contactId = contactId;
        this.contactHash = contact.hash();
        this.contactMasked = contact.masked();
        this.contactValue = contact.value();
        this.status = ConversationStatus.OPEN;
        apply(owner == null ? Owner.NONE : owner, now);
    }

    /**
     * Contato da conversa: valor normalizado (criptografado no banco), blind index e máscara.
     */
    public record Contact(String value, String hash, String masked) {

        public Contact {
            Guard.require(value != null && !value.isBlank(), "conversation.contact_required", "Contato obrigatório");
            Guard.require(hash != null && !hash.isBlank(), "conversation.contact_required", "Contato obrigatório");
            masked = masked == null || masked.isBlank() ? "***" : masked;
        }
    }

    /** Dono do atendimento (tipo, operador, equipe e perfil exigido). */
    public record Owner(OwnerType type, UUID userId, UUID teamId, String requiredProfile) {

        public static final Owner NONE = new Owner(OwnerType.NONE, null, null, null);

        public Owner {
            Guard.require(type != null, "conversation.owner_required", "Dono obrigatório");
            Guard.require((type == OwnerType.USER) == (userId != null), "conversation.owner_user_invalid",
                    "Operador obrigatório somente quando o dono é USER");
            Guard.require(requiredProfile == null || requiredProfile.length() <= 40, "conversation.profile_invalid",
                    "Perfil exigido até 40 caracteres");
        }

        public static Owner user(UUID userId, UUID teamId) {
            return new Owner(OwnerType.USER, userId, teamId, null);
        }

        public static Owner queue(UUID teamId, String requiredProfile) {
            return new Owner(OwnerType.QUEUE, null, teamId, requiredProfile);
        }

        public static Owner assistant() {
            return new Owner(OwnerType.ASSISTANT, null, null, null);
        }
    }

    /**
     * Muda o dono. O perfil exigido acompanha a espera na fila e é mantido quando um operador
     * assume (indica a alçada esperada de quem atende).
     *
     * @return dono anterior
     */
    public Owner changeOwner(Owner owner, Instant now) {
        Guard.requireState(status == ConversationStatus.OPEN, "conversation.closed", "Conversa encerrada");
        Owner previous = owner();
        apply(owner, now);
        return previous;
    }

    private void apply(Owner owner, Instant now) {
        boolean changed = ownerType != owner.type() || !java.util.Objects.equals(ownerUserId, owner.userId());
        this.ownerType = owner.type();
        this.ownerUserId = owner.userId();
        this.ownerTeamId = owner.teamId();
        if (owner.type() == OwnerType.QUEUE || owner.type() == OwnerType.ASSISTANT || owner.type() == OwnerType.NONE) {
            this.requiredProfile = owner.requiredProfile();
        } else if (owner.requiredProfile() != null) {
            this.requiredProfile = owner.requiredProfile();
        }
        if (changed || ownerSince == null) {
            this.ownerSince = now;
        }
    }

    public Owner owner() {
        return new Owner(ownerType, ownerUserId, ownerTeamId, requiredProfile);
    }

    /** Vincula a conversa a um caso (identificação do devedor ou caso aberto depois do contato). */
    public void linkCase(UUID debtorId, UUID portfolioId, UUID caseId) {
        Guard.require(debtorId != null, "conversation.debtor_required", "Devedor obrigatório");
        Guard.requireState(this.debtorId == null || this.debtorId.equals(debtorId), "conversation.debtor_mismatch",
                "A conversa pertence a outro devedor");
        Guard.requireState(creditorId != null || caseId == null, "conversation.creditor_required",
                "Conversa sem credor não pode ser vinculada a caso");
        this.debtorId = debtorId;
        this.portfolioId = portfolioId;
        this.caseId = caseId;
    }

    /**
     * Identificação do devedor (e do credor, se a conversa ainda não tem) de uma conversa iniciada
     * por contato desconhecido.
     */
    public void identify(UUID debtorId, UUID creditorId, UUID contactId) {
        Guard.requireState(this.debtorId == null, "conversation.already_identified", "Conversa já identificada");
        Guard.require(debtorId != null && creditorId != null, "conversation.identification_required",
                "Devedor e credor obrigatórios");
        Guard.requireState(this.creditorId == null || this.creditorId.equals(creditorId), "conversation.creditor_mismatch",
                "A conversa pertence a outro credor");
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.contactId = contactId;
    }

    public void useContact(UUID contactId) {
        this.contactId = contactId;
    }

    /** Mensagem recebida do devedor: abre a janela do WhatsApp e a espera por resposta. */
    public void registerInbound(Instant at) {
        Guard.requireState(status == ConversationStatus.OPEN, "conversation.closed", "Conversa encerrada");
        this.lastInboundAt = lastInboundAt == null || at.isAfter(lastInboundAt) ? at : lastInboundAt;
        this.lastMessageAt = lastMessageAt == null || at.isAfter(lastMessageAt) ? at : lastMessageAt;
        this.messageCount++;
        this.unreadCount++;
        if (awaitingSince == null) {
            this.awaitingSince = at;
        }
    }

    /**
     * Mensagem enviada (enfileirada). Resposta de operador ou assistente encerra a espera.
     *
     * @return tempo de espera encerrado por esta resposta, se havia espera
     */
    public Optional<Duration> registerOutbound(SenderType sender, Instant at) {
        Guard.requireState(status == ConversationStatus.OPEN, "conversation.closed", "Conversa encerrada");
        this.lastOutboundAt = at;
        this.lastMessageAt = at;
        this.messageCount++;
        if ((sender == SenderType.USER || sender == SenderType.ASSISTANT) && awaitingSince != null) {
            Duration waited = Duration.between(awaitingSince, at);
            this.awaitingSince = null;
            this.unreadCount = 0;
            return Optional.of(waited.isNegative() ? Duration.ZERO : waited);
        }
        return Optional.empty();
    }

    public void markRead() {
        this.unreadCount = 0;
    }

    /** Janela de atendimento do WhatsApp aberta (mensagem livre permitida). */
    public boolean sessionWindowOpen(Instant now) {
        return channel != ChannelType.WHATSAPP
                || (lastInboundAt != null && now.isBefore(lastInboundAt.plus(WHATSAPP_SESSION_WINDOW)));
    }

    public void close(UUID by, String reason, Instant now) {
        Guard.requireState(status == ConversationStatus.OPEN, "conversation.closed", "Conversa já encerrada");
        Guard.require(reason == null || reason.length() <= 300, "conversation.reason_too_long", "Motivo até 300 caracteres");
        this.status = ConversationStatus.CLOSED;
        this.closedAt = now;
        this.closedBy = by;
        this.closeReason = reason == null || reason.isBlank() ? null : reason.trim();
        this.awaitingSince = null;
        this.unreadCount = 0;
        apply(Owner.NONE, now);
    }

    public boolean isOpen() {
        return status == ConversationStatus.OPEN;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public UUID getChannelAccountId() {
        return channelAccountId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public String getContactHash() {
        return contactHash;
    }

    public String getContactMasked() {
        return contactMasked;
    }

    /** Valor do contato em claro: uso exclusivo do envio; nunca registrar em log. */
    public String getContactValue() {
        return contactValue;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public OwnerType getOwnerType() {
        return ownerType;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public UUID getOwnerTeamId() {
        return ownerTeamId;
    }

    public String getRequiredProfile() {
        return requiredProfile;
    }

    public Instant getOwnerSince() {
        return ownerSince;
    }

    public Instant getLastInboundAt() {
        return lastInboundAt;
    }

    public Instant getLastOutboundAt() {
        return lastOutboundAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Instant getAwaitingSince() {
        return awaitingSince;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getClosedBy() {
        return closedBy;
    }

    public String getCloseReason() {
        return closeReason;
    }
}
