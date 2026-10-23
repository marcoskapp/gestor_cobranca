package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Evidência do aceite do devedor (§6): canal, instante, quem registrou (operador, assistente,
 * portal), identificador externo da interação (mensagem, sessão do portal, chamada), IP e agente
 * de usuário quando houver, e o termo aceito (arquivo no {@code FileStorage} com SHA-256).
 * Imutável; IP e agente são apagados na anonimização do devedor (o hash do termo permanece).
 */
@Embeddable
public class AcceptanceEvidence {

    public static final Set<String> CHANNELS = Set.of("PHONE", "WHATSAPP", "EMAIL", "SMS", "CHAT", "PORTAL", "IN_PERSON");

    @Column(name = "acceptance_channel", length = 20)
    private String channel;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_by_type", length = 20)
    private String actorType;

    @Column(name = "accepted_by_id")
    private UUID actorId;

    @Column(name = "acceptance_reference", length = 120)
    private String reference;

    @Column(name = "acceptance_ip", length = 64)
    private String ip;

    @Column(name = "acceptance_user_agent", length = 300)
    private String userAgent;

    @Column(name = "term_file_id")
    private UUID termFileId;

    @Column(name = "term_file_key", length = 500)
    private String termFileKey;

    @Column(name = "term_sha256", length = 64)
    private String termSha256;

    @Column(name = "term_size")
    private Long termSize;

    protected AcceptanceEvidence() {
    }

    public AcceptanceEvidence(String channel, Instant acceptedAt, String actorType, UUID actorId, String reference,
                              String ip, String userAgent, UUID termFileId, String termFileKey, String termSha256,
                              long termSize) {
        Guard.require(channel != null && CHANNELS.contains(channel), "proposal.acceptance_channel_invalid",
                "Canal do aceite inválido");
        Guard.require(acceptedAt != null && actorType != null && actorId != null, "proposal.acceptance_invalid",
                "Instante e autor do aceite obrigatórios");
        Guard.require(termFileKey != null && termSha256 != null && termSha256.matches("[0-9a-f]{64}"),
                "proposal.acceptance_term_required", "O aceite exige o termo armazenado com hash");
        Guard.require(reference == null || reference.length() <= 120, "proposal.acceptance_reference_invalid",
                "Referência do aceite até 120 caracteres");
        this.channel = channel;
        this.acceptedAt = acceptedAt;
        this.actorType = actorType;
        this.actorId = actorId;
        this.reference = reference;
        this.ip = truncate(ip, 64);
        this.userAgent = truncate(userAgent, 300);
        this.termFileId = termFileId;
        this.termFileKey = termFileKey;
        this.termSha256 = termSha256;
        this.termSize = termSize;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Cópia (o acordo guarda a mesma evidência da proposta aceita). */
    public AcceptanceEvidence copy() {
        return new AcceptanceEvidence(channel, acceptedAt, actorType, actorId, reference, ip, userAgent, termFileId,
                termFileKey, termSha256, termSize == null ? 0 : termSize);
    }

    /** Cópia sem IP e agente de usuário (anonimização do devedor). */
    public AcceptanceEvidence withoutNetworkData() {
        return new AcceptanceEvidence(channel, acceptedAt, actorType, actorId, reference, null, null, termFileId,
                termFileKey, termSha256, termSize == null ? 0 : termSize);
    }

    public String getChannel() {
        return channel;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public String getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getReference() {
        return reference;
    }

    public String getIp() {
        return ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public UUID getTermFileId() {
        return termFileId;
    }

    public String getTermFileKey() {
        return termFileKey;
    }

    public String getTermSha256() {
        return termSha256;
    }

    public Long getTermSize() {
        return termSize;
    }
}
