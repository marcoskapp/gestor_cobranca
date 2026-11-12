package br.com.gestorcobranca.debt.domain.model;

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
import java.util.Objects;
import java.util.UUID;

/**
 * Contato de um devedor. Agregado próprio (referencia o devedor por id): contatos mudam com
 * frequência e de forma independente do cadastro (validação por provedores, retorno de entrega,
 * marcação por operador), sem disputar o lock otimista do devedor.
 * <p>
 * O valor é imutável — outro telefone é outro contato — e fica criptografado; {@code valueHash}
 * é o blind index que impede duplicidade por (devedor, tipo, valor).
 */
@Entity
@Table(name = "debtor_contact", schema = "debt")
public class DebtorContact extends TenantScopedEntity {

    static final int PERSON_NAME_MAX = 120;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private ContactType type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "value_encrypted", nullable = false, length = 2048, updatable = false)
    private String value;

    @Column(name = "value_hash", nullable = false, length = 64, updatable = false)
    private String valueHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false, length = 20)
    private ContactLabel label;

    @Column(name = "whatsapp", nullable = false)
    private boolean whatsapp;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private ContactSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContactStatus status;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "invalid_reason", length = 500)
    private String invalidReason;

    /** Nome da pessoa com quem se fala neste contato (B2); dado pessoal, criptografado. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "person_name_encrypted", length = 1024)
    private String personName;

    @Enumerated(EnumType.STRING)
    @Column(name = "person_role", length = 20)
    private ContactPersonRole personRole;

    /** A pessoa deste contato decide pelo devedor (ex.: quem aprova o pagamento na empresa). */
    @Column(name = "decision_maker", nullable = false)
    private boolean decisionMaker;
    /** Carga que criou o contato (nulo fora de carga). */
    @Column(name = "first_import_batch_id", updatable = false)
    private UUID firstImportBatchId;

    /** Última carga que enviou o contato (criação ou reenvio) e quando. */
    @Column(name = "last_import_batch_id")
    private UUID lastImportBatchId;

    @Column(name = "last_received_at")
    private Instant lastReceivedAt;

    /** Chegou numa carga para devedor que já existia (contatos da carga que criou o devedor nunca são novos). */
    @Column(name = "received_after_registration", nullable = false)
    private boolean receivedAfterRegistration;

    /** Fim da janela de novidade (nulo = nunca foi novo). */
    @Column(name = "novelty_until")
    private Instant noveltyUntil;

    /** Primeira tentativa e primeiro contato efetivo por este contato, de qualquer origem (gravados uma única vez). */
    @Column(name = "first_attempt_at")
    private Instant firstAttemptAt;

    @Column(name = "first_effective_at")
    private Instant firstEffectiveAt;

    protected DebtorContact() {
    }

    /**
     * @param valueHash blind index de {@code value.hashKey()} com finalidade {@code value.hashPurpose()}
     */
    public DebtorContact(UUID debtorId, ContactValue value, String valueHash, ContactLabel label, boolean whatsapp,
                         ContactSource source, int priority) {
        Guard.require(debtorId != null, "contact.debtor_required", "Devedor obrigatório");
        Objects.requireNonNull(value, "value");
        Guard.require(valueHash != null && !valueHash.isBlank(), "contact.hash_required", "Índice do contato obrigatório");
        Guard.require(source != null, "contact.source_required", "Origem do contato obrigatória");
        this.debtorId = debtorId;
        this.type = value.type();
        this.value = value.value();
        this.valueHash = valueHash;
        this.source = source;
        this.status = ContactStatus.UNVERIFIED;
        classify(label, whatsapp, priority);
    }

    /** Altera classificação, indicação de WhatsApp e prioridade (menor = usado primeiro). */
    public void classify(ContactLabel label, boolean whatsapp, int priority) {
        Guard.require(!whatsapp || type == ContactType.PHONE, "contact.whatsapp_requires_phone",
                "Somente telefones podem ser marcados como WhatsApp");
        Guard.require(priority >= 0, "contact.priority_invalid", "Prioridade não pode ser negativa");
        this.label = label == null ? ContactLabel.OTHER : label;
        this.whatsapp = whatsapp;
        this.priority = priority;
    }

    /**
     * Pessoa de contato (B2): nome (até {@value #PERSON_NAME_MAX} caracteres), papel e se decide. Sem nome nem papel,
     * a indicação de quem decide não faz sentido ({@code contact.decision_maker_without_person}).
     */
    public void assignPerson(String name, ContactPersonRole role, boolean decisionMaker) {
        String clean = name == null || name.isBlank() ? null : name.trim().replaceAll("\\s+", " ");
        Guard.require(clean == null || clean.length() <= PERSON_NAME_MAX, "contact.person_name_too_long",
                "Nome da pessoa de contato muito longo");
        Guard.require(!decisionMaker || clean != null || role != null, "contact.decision_maker_without_person",
                "Informe quem é a pessoa de contato que decide");
        this.personName = clean;
        this.personRole = role;
        this.decisionMaker = decisionMaker;
    }

    public boolean hasPerson() {
        return personName != null || personRole != null;
    }

    public void markValid(Instant now) {
        this.status = ContactStatus.VALID;
        this.invalidReason = null;
        this.lastVerifiedAt = now;
    }

    public void markInvalid(String reason, Instant now) {
        Guard.require(reason != null && !reason.isBlank(), "contact.invalid_reason_required",
                "Informe o motivo da invalidação");
        Guard.require(reason.length() <= 500, "contact.invalid_reason_too_long", "Motivo muito longo");
        this.status = ContactStatus.INVALID;
        this.invalidReason = reason.trim();
        this.lastVerifiedAt = now;
    }

    public ContactValue contactValue() {
        return new ContactValue(type, value);
    }

    public String maskedValue() {
        return contactValue().masked();
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public ContactType getType() {
        return type;
    }

    /** Valor completo (descriptografado). Uso restrito: revelação auditada ou envio pelo canal. */
    public String getValue() {
        return value;
    }

    public String getValueHash() {
        return valueHash;
    }

    public ContactLabel getLabel() {
        return label;
    }

    public boolean isWhatsapp() {
        return whatsapp;
    }

    public ContactSource getSource() {
        return source;
    }

    public ContactStatus getStatus() {
        return status;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public String getInvalidReason() {
        return invalidReason;
    }

    /** Nome da pessoa de contato (descriptografado); exibido só a quem vê o contato. */
    public String getPersonName() {
        return personName;
    }

    public ContactPersonRole getPersonRole() {
        return personRole;
    }

    public boolean isDecisionMaker() {
        return decisionMaker;
    }

    // ------------------------------------------------------------------ recebimento por carga (contatos-recebidos.md)

    /**
     * Contato recém-criado por uma carga. Se o devedor já existia antes da carga, abre a janela de novidade.
     *
     * @param afterRegistration o devedor existia antes desta carga
     * @param window            prazo da janela de novidade da assessoria (exigido quando {@code afterRegistration})
     */
    public void receivedByImport(UUID importBatchId, boolean afterRegistration, Duration window, Instant now) {
        Guard.require(importBatchId != null, "contact.import_batch_required", "Carga obrigatória");
        Guard.requireState(firstImportBatchId == null && lastImportBatchId == null, "contact.already_received",
                "Recebimento da carga já registrado");
        Guard.require(!afterRegistration || (window != null && window.isPositive()), "contact.novelty_window_invalid",
                "Janela de novidade inválida");
        this.firstImportBatchId = importBatchId;
        this.lastImportBatchId = importBatchId;
        this.lastReceivedAt = now;
        this.receivedAfterRegistration = afterRegistration;
        this.noveltyUntil = afterRegistration ? now.plus(window) : null;
    }

    /**
     * O credor mandou de novo um contato que o devedor já tem: confirma que o dado continua atual. Reprocessar a mesma
     * carga não muda nada.
     *
     * @return true se registrou um reenvio
     */
    public boolean resentByImport(UUID importBatchId, Instant now) {
        Guard.require(importBatchId != null, "contact.import_batch_required", "Carga obrigatória");
        if (importBatchId.equals(lastImportBatchId) || importBatchId.equals(firstImportBatchId)) {
            return false;
        }
        this.lastImportBatchId = importBatchId;
        this.lastReceivedAt = now;
        return true;
    }

    /**
     * Uso do contato (ligação, mensagem ou interação por ele). Só a primeira tentativa e o primeiro contato efetivo
     * contam; contato efetivo sem tentativa registrada antes (ex.: resposta recebida) conta também como tentativa.
     *
     * @return {@code true} se algo foi registrado agora (primeira tentativa ou primeiro contato efetivo)
     */
    public boolean recordUse(Instant at, boolean effective) {
        Objects.requireNonNull(at, "at");
        boolean changed = false;
        if (firstAttemptAt == null) {
            this.firstAttemptAt = at;
            changed = true;
        }
        if (effective && firstEffectiveAt == null) {
            this.firstEffectiveAt = at;
            changed = true;
        }
        return changed;
    }

    /** Contato novo ainda sem tentativa, dentro da janela e utilizável (entra no fim da novidade do devedor). */
    public boolean isNoveltyPending(Instant now) {
        return novelty(now) == ContactNovelty.NEW;
    }

    /** Situação do selo agora; nulo = sem selo (nunca novo, fora da janela ou inválido). */
    public ContactNovelty novelty(Instant now) {
        if (!receivedAfterRegistration || noveltyUntil == null || !noveltyUntil.isAfter(now) || !status.isUsable()) {
            return null;
        }
        if (firstAttemptAt == null) {
            return ContactNovelty.NEW;
        }
        return firstEffectiveAt != null ? ContactNovelty.NEW_ANSWERED : ContactNovelty.NEW_NO_ANSWER;
    }

    /** Último reenvio pelo credor (nulo se a última carga que enviou o contato foi a que o criou, ou nunca houve carga). */
    public Instant confirmedByCreditorAt() {
        return lastImportBatchId != null && !lastImportBatchId.equals(firstImportBatchId) ? lastReceivedAt : null;
    }

    /** Data em que a carga criou o contato (nulo fora de carga). */
    public Instant receivedAt() {
        return firstImportBatchId == null ? null : getCreatedAt();
    }

    public UUID getFirstImportBatchId() {
        return firstImportBatchId;
    }

    public UUID getLastImportBatchId() {
        return lastImportBatchId;
    }

    public Instant getLastReceivedAt() {
        return lastReceivedAt;
    }

    public boolean isReceivedAfterRegistration() {
        return receivedAfterRegistration;
    }

    public Instant getNoveltyUntil() {
        return noveltyUntil;
    }

    public Instant getFirstAttemptAt() {
        return firstAttemptAt;
    }

    public Instant getFirstEffectiveAt() {
        return firstEffectiveAt;
    }
}
