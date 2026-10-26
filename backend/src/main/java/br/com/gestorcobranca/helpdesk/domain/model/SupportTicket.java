package br.com.gestorcobranca.helpdesk.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Chamado de suporte (issue no GitHub) preparado pela IA. O texto já vem mascarado e montado pelo servidor
 * ({@code TicketComposer}); fica cifrado aqui porque leva o e-mail de quem abriu. A abertura é idempotente: o
 * marcador {@link #marker()} vai no corpo da issue e permite reencontrá-la se a abertura for interrompida entre a
 * chamada ao GitHub e a gravação do número.
 */
@Entity
@DynamicUpdate
@Table(name = "support_ticket", schema = "helpdesk")
public class SupportTicket extends TenantScopedEntity {

    /** Abertura em andamento há mais que isto é considerada interrompida (nova tentativa procura o marcador). */
    public static final Duration CREATING_STALE_AFTER = Duration.ofMinutes(2);

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "title_encrypted", nullable = false, length = 2000, updatable = false)
    private String title;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "body_encrypted", nullable = false, length = 40000, updatable = false)
    private String body;

    @Column(name = "content_sha256", nullable = false, length = 64, updatable = false)
    private String contentSha256;

    @Column(name = "screen_key", length = 64, updatable = false)
    private String screenKey;

    @Column(name = "repository", nullable = false, length = 140, updatable = false)
    private String repository;

    @Column(name = "drafted_at", nullable = false, updatable = false)
    private Instant draftedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "creating_since")
    private Instant creatingSince;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "issue_number")
    private Long issueNumber;

    @Column(name = "issue_url", length = 300)
    private String issueUrl;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "duplicate_of_id")
    private UUID duplicateOfId;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    protected SupportTicket() {
    }

    public SupportTicket(UUID conversationId, UUID userId, String title, String body, String contentSha256,
                         String screenKey, String repository, Instant now, Duration validity) {
        Guard.require(title != null && !title.isBlank() && title.length() <= 200, "helpdesk.ticket.title_invalid",
                "Título do chamado inválido");
        Guard.require(body != null && !body.isBlank() && body.length() <= 20_000, "helpdesk.ticket.body_invalid",
                "Descrição do chamado inválida");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.title = title;
        this.body = body;
        this.contentSha256 = contentSha256;
        this.screenKey = screenKey;
        this.repository = repository;
        this.status = TicketStatus.DRAFT;
        this.draftedAt = now;
        this.expiresAt = now.plus(validity);
    }

    /** Marcador de idempotência gravado no corpo da issue (comentário HTML, invisível na página). */
    public String marker() {
        return "gestor-cobranca:helpdesk-ticket:" + getId();
    }

    public boolean ownedBy(UUID actorId) {
        return userId.equals(actorId);
    }

    /**
     * Início da abertura. {@code DRAFT}/{@code FAILED} dentro da validade, ou {@code CREATING} interrompido.
     *
     * @return {@code true} se a abertura anterior foi interrompida (a issue pode já existir: procurar o marcador)
     */
    public boolean startCreation(Instant now) {
        if (status == TicketStatus.CREATING) {
            Guard.requireState(creatingSince != null && creatingSince.plus(CREATING_STALE_AFTER).isBefore(now),
                    "helpdesk.ticket.in_progress", "O chamado já está sendo aberto");
            creatingSince = now;
            attempts++;
            return true;
        }
        Guard.requireState(status == TicketStatus.DRAFT || status == TicketStatus.FAILED,
                "helpdesk.ticket.not_pending", "O chamado não está aguardando confirmação");
        Guard.requireState(now.isBefore(expiresAt), "helpdesk.ticket.expired",
                "O rascunho do chamado expirou; peça ao chat para prepará-lo de novo");
        boolean retry = status == TicketStatus.FAILED && attempts > 0;
        status = TicketStatus.CREATING;
        creatingSince = now;
        attempts++;
        errorCode = null;
        return retry;
    }

    public void opened(long number, String url, Instant now) {
        Guard.requireState(status == TicketStatus.CREATING, "helpdesk.ticket.not_creating", "Abertura não iniciada");
        status = TicketStatus.OPENED;
        issueNumber = number;
        issueUrl = url;
        openedAt = now;
        creatingSince = null;
    }

    /** Mesmo texto já aberto pela mesma pessoa: aponta para a issue existente em vez de abrir outra. */
    public void duplicateOf(SupportTicket existing, Instant now) {
        Guard.requireState(status == TicketStatus.CREATING, "helpdesk.ticket.not_creating", "Abertura não iniciada");
        status = TicketStatus.DUPLICATE;
        duplicateOfId = existing.getId();
        issueNumber = existing.issueNumber;
        issueUrl = existing.issueUrl;
        openedAt = now;
        creatingSince = null;
    }

    public void failed(String code) {
        Guard.requireState(status == TicketStatus.CREATING, "helpdesk.ticket.not_creating", "Abertura não iniciada");
        status = TicketStatus.FAILED;
        errorCode = code;
        creatingSince = null;
    }

    public void cancel() {
        Guard.requireState(status == TicketStatus.DRAFT || status == TicketStatus.FAILED,
                "helpdesk.ticket.not_pending", "O chamado não está aguardando confirmação");
        status = TicketStatus.CANCELLED;
    }

    public void expire(Instant now) {
        if ((status == TicketStatus.DRAFT || status == TicketStatus.FAILED) && !now.isBefore(expiresAt)) {
            status = TicketStatus.EXPIRED;
        }
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getScreenKey() {
        return screenKey;
    }

    public String getRepository() {
        return repository;
    }

    public Instant getDraftedAt() {
        return draftedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getIssueNumber() {
        return issueNumber;
    }

    public String getIssueUrl() {
        return issueUrl;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getAttempts() {
        return attempts;
    }

    public UUID getDuplicateOfId() {
        return duplicateOfId;
    }
}
