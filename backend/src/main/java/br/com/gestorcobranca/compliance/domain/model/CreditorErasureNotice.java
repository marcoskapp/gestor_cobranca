package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aviso ao credor de que o titular foi anonimizado (LGPD art. 18 §6º; SEG-20261124-D01h, decisão 1478): o credor
 * precisa eliminar o titular no sistema dele e deixar de mandar as linhas dos títulos citados nas cargas. Um aviso por
 * par devedor × credor, aberto pelo portão único da eliminação na mesma transação da anonimização. Nada é enviado pelo
 * sistema: a pessoa avisa o credor pelo canal da assessoria e registra canal e data (sem texto livre).
 * <p>
 * Sem dado pessoal: o titular só pelo id do registro anonimizado; os títulos pela referência do credor já mascarada.
 */
@Entity
@Table(name = "creditor_erasure_notice", schema = "compliance")
public class CreditorErasureNotice extends TenantScopedEntity {

    /** O que levou à anonimização. */
    public enum Origin { DATA_SUBJECT_REQUEST, RETENTION }

    public enum Status { PENDING, NOTIFIED }

    /** Canal pelo qual a assessoria avisou o credor. */
    public enum Channel { EMAIL, PHONE, LETTER, MEETING, OTHER }

    /**
     * Limite da referência mascarada: pior caso de 80 caracteres do credor × expansão máxima do NFKC (18). Nunca
     * truncar (o credor não acharia o título) nem recusar por isso (bloquearia a eliminação).
     */
    public static final int MAX_REFERENCE = 80 * 18;
    /** Relógio do navegador adiantado em relação ao servidor (decisão 1485). */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 30, updatable = false)
    private Origin origin;

    /** Solicitação do titular ou execução de retenção. */
    @Column(name = "origin_id", nullable = false, updatable = false)
    private UUID originId;

    @Column(name = "erased_at", nullable = false, updatable = false)
    private Instant erasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "notified_by")
    private UUID notifiedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "notified_channel", length = 20)
    private Channel notifiedChannel;

    /** Quando o aviso foi registrado no sistema (pode ser depois de {@code notifiedAt}). */
    @Column(name = "registered_at")
    private Instant registeredAt;

    /** Carregados só no detalhe (a lista não os mostra). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "creditor_erasure_notice_item", schema = "compliance",
            joinColumns = @JoinColumn(name = "notice_id"))
    @OrderColumn(name = "position")
    private List<Item> items = new ArrayList<>();

    protected CreditorErasureNotice() {
    }

    public CreditorErasureNotice(UUID debtorId, UUID creditorId, Origin origin, UUID originId, Instant erasedAt,
                                 List<Item> items) {
        Guard.require(debtorId != null && creditorId != null, "creditor_erasure_notice.subject_required",
                "Titular e credor obrigatórios");
        Guard.require(origin != null && originId != null, "creditor_erasure_notice.origin_required",
                "Origem da anonimização obrigatória");
        Guard.require(items != null && !items.isEmpty(), "creditor_erasure_notice.items_required",
                "O aviso precisa citar ao menos um título do credor");
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.origin = origin;
        this.originId = originId;
        this.erasedAt = Guard.requireNonNull(erasedAt, "creditor_erasure_notice.erased_at_required",
                "Data da anonimização obrigatória");
        this.status = Status.PENDING;
        this.items = new ArrayList<>(items);
    }

    /**
     * Registra que a assessoria avisou o credor. Uma vez só (o aviso é a evidência do art. 18 §6º; corrigir seria
     * reescrever a evidência). A data do aviso não pode ser futura nem anterior à anonimização.
     */
    public void registerNotified(Instant notifiedAt, Channel channel, UUID by, Instant now) {
        Guard.requireState(status == Status.PENDING, "creditor_erasure_notice.already_notified",
                "O aviso a este credor já foi registrado");
        Guard.requireNonNull(notifiedAt, "creditor_erasure_notice.notified_at_required", "Informe quando o credor foi avisado");
        Guard.requireNonNull(channel, "creditor_erasure_notice.channel_required", "Informe o canal do aviso");
        Guard.require(by != null, "creditor_erasure_notice.actor_required", "Quem registra o aviso é obrigatório");
        Guard.require(!notifiedAt.isAfter(now.plus(FUTURE_TOLERANCE)), "creditor_erasure_notice.notified_at_future",
                "A data do aviso não pode ser futura");
        Guard.require(!notifiedAt.isBefore(erasedAt), "creditor_erasure_notice.notified_at_before_erasure",
                "A data do aviso não pode ser anterior à anonimização");
        this.status = Status.NOTIFIED;
        this.notifiedAt = notifiedAt;
        this.notifiedChannel = channel;
        this.notifiedBy = by;
        this.registeredAt = now;
    }

    public UUID getDebtorId() { return debtorId; }
    public UUID getCreditorId() { return creditorId; }
    public Origin getOrigin() { return origin; }
    public UUID getOriginId() { return originId; }
    public Instant getErasedAt() { return erasedAt; }
    public Status getStatus() { return status; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public UUID getNotifiedBy() { return notifiedBy; }
    public Channel getNotifiedChannel() { return notifiedChannel; }
    public Instant getRegisteredAt() { return registeredAt; }
    public List<Item> getItems() { return List.copyOf(items); }

    /** Título do credor citado no aviso: referência já mascarada e parcela. */
    @Embeddable
    public static class Item {

        @Column(name = "portfolio_id", nullable = false, updatable = false)
        private UUID portfolioId;

        @Column(name = "reference", nullable = false, length = MAX_REFERENCE, updatable = false)
        private String reference;

        @Column(name = "installment_number", updatable = false)
        private Integer installmentNumber;

        protected Item() {
        }

        /** @param maskedReference referência do título no credor, com CPF/CNPJ embutido já mascarado */
        public Item(UUID portfolioId, String maskedReference, Integer installmentNumber) {
            Guard.require(portfolioId != null, "creditor_erasure_notice.portfolio_required", "Carteira obrigatória");
            Guard.require(maskedReference != null && !maskedReference.isBlank()
                            && maskedReference.length() <= MAX_REFERENCE, "creditor_erasure_notice.reference_invalid",
                    "Referência do título inválida");
            this.portfolioId = portfolioId;
            this.reference = maskedReference;
            this.installmentNumber = installmentNumber;
        }

        public UUID getPortfolioId() { return portfolioId; }
        public String getReference() { return reference; }
        public Integer getInstallmentNumber() { return installmentNumber; }
    }
}
