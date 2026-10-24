package br.com.gestorcobranca.portal.domain.model;

import br.com.gestorcobranca.portal.domain.service.AccessSecrets;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Desafio de acesso ao portal: código (OTP) enviado ao contato cadastrado ou a etapa seguinte ao
 * resgate do link, com o desafio de conhecimento do credor quando configurado.
 * <p>
 * Desafio <strong>fictício</strong> ({@code debtorId} nulo): criado para documento inexistente ou
 * sem dívida no portal, com código aleatório que ninguém recebe — a resposta ao pedido e às
 * tentativas é a mesma de um desafio real, e ele nunca é aceito (anti-enumeração).
 */
@Entity
@Table(name = "access_challenge", schema = "portal")
public class AccessChallenge extends TenantScopedEntity {

    @Column(name = "configuration_id", nullable = false, updatable = false)
    private UUID configurationId;

    @Column(name = "debtor_id")
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 10, updatable = false)
    private AccessMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, updatable = false)
    private PortalChannel channel;

    @Column(name = "document_hash", length = 64)
    private String documentHash;

    @Column(name = "code_hash", length = 64, updatable = false)
    private String codeHash;

    @Column(name = "link_id", updatable = false)
    private UUID linkId;

    @Column(name = "message_id")
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "knowledge_challenge", nullable = false, length = 20, updatable = false)
    private KnowledgeChallenge knowledgeChallenge;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ChallengeStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AccessChallenge() {
    }

    private AccessChallenge(UUID configurationId, UUID debtorId, AccessMethod method, PortalChannel channel,
                            String documentHash, String codeHash, UUID linkId, KnowledgeChallenge knowledge,
                            int maxAttempts, Instant expiresAt) {
        this.configurationId = Guard.requireNonNull(configurationId, "portal.configuration_required", "Portal obrigatório");
        this.debtorId = debtorId;
        this.method = method;
        this.channel = channel;
        this.documentHash = documentHash;
        this.codeHash = codeHash;
        this.linkId = linkId;
        this.knowledgeChallenge = knowledge == null ? KnowledgeChallenge.NONE : knowledge;
        Guard.require(maxAttempts >= 1 && maxAttempts <= 10, "portal.max_attempts_invalid", "Tentativas entre 1 e 10");
        this.maxAttempts = maxAttempts;
        this.attempts = 0;
        this.status = ChallengeStatus.PENDING;
        this.expiresAt = Guard.requireNonNull(expiresAt, "portal.expiration_required", "Validade obrigatória");
    }

    /**
     * @param debtorId nulo = desafio fictício (documento inexistente ou fora do portal)
     * @param codeHash HMAC do código (o código em claro só existe na mensagem enviada)
     */
    public static AccessChallenge otp(UUID configurationId, UUID debtorId, PortalChannel channel, String documentHash,
                                      String codeHash, KnowledgeChallenge knowledge, int maxAttempts, Instant expiresAt) {
        Guard.require(documentHash != null && codeHash != null, "portal.challenge_invalid", "Desafio inválido");
        return new AccessChallenge(configurationId, debtorId, AccessMethod.OTP, channel, documentHash, codeHash, null,
                knowledge, maxAttempts, expiresAt);
    }

    /** Etapa de conhecimento após o link (a posse já foi provada pelo link). */
    public static AccessChallenge afterLink(UUID configurationId, UUID debtorId, UUID linkId, KnowledgeChallenge knowledge,
                                            int maxAttempts, Instant expiresAt) {
        Guard.require(debtorId != null && linkId != null, "portal.challenge_invalid", "Desafio inválido");
        return new AccessChallenge(configurationId, debtorId, AccessMethod.LINK, null, null, null, linkId, knowledge,
                maxAttempts, expiresAt);
    }

    public boolean isDecoy() {
        return debtorId == null;
    }

    public boolean openAt(Instant now) {
        return status == ChallengeStatus.PENDING && expiresAt.isAfter(now) && attempts < maxAttempts;
    }

    /** Confere o HMAC do código informado (tempo constante); o link não tem código. */
    public boolean codeMatches(String providedHash) {
        return method == AccessMethod.LINK || AccessSecrets.constantTimeEquals(codeHash, providedHash);
    }

    /** Registra tentativa malsucedida; esgotadas as tentativas, o desafio falha. */
    public void fail(Instant now) {
        attempts = Math.min(maxAttempts, attempts + 1);
        if (attempts >= maxAttempts) {
            status = ChallengeStatus.FAILED;
            completedAt = now;
        }
    }

    public void verify(Instant now) {
        Guard.requireState(!isDecoy() && openAt(now), "portal.challenge_closed", "Desafio encerrado");
        attempts = Math.min(maxAttempts, attempts + 1);
        status = ChallengeStatus.VERIFIED;
        completedAt = now;
    }

    public void expireIfDue(Instant now) {
        if (status == ChallengeStatus.PENDING && !expiresAt.isAfter(now)) {
            status = ChallengeStatus.EXPIRED;
            completedAt = now;
        }
    }

    public void sentAs(UUID messageId) {
        this.messageId = messageId;
    }

    /** LGPD: anonimização do devedor remove o vínculo e o índice do documento. */
    public void anonymize() {
        this.documentHash = null;
    }

    public UUID getConfigurationId() { return configurationId; }
    public UUID getDebtorId() { return debtorId; }
    public AccessMethod getMethod() { return method; }
    public PortalChannel getChannel() { return channel; }
    public String getDocumentHash() { return documentHash; }
    public UUID getLinkId() { return linkId; }
    public UUID getMessageId() { return messageId; }
    public KnowledgeChallenge getKnowledgeChallenge() { return knowledgeChallenge; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public ChallengeStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCompletedAt() { return completedAt; }
}
