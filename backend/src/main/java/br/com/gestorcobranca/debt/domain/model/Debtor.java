package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Devedor (pessoa física ou jurídica). A identidade é o documento (RB01): dois cadastros com o
 * mesmo nome nunca são mesclados; dois títulos de credores diferentes com o mesmo documento
 * pertencem ao mesmo devedor.
 * <p>
 * O documento é gravado criptografado; {@code documentHash} é o blind index (HMAC por tenant,
 * finalidade {@link #DOCUMENT_HASH_PURPOSE}) usado para busca e unicidade. O nome fica em
 * claro porque é necessário para busca e exibição na operação.
 */
@Entity
@Table(name = "debtor", schema = "debt")
public class Debtor extends TenantScopedEntity {

    /** Finalidade do blind index do documento. */
    public static final String DOCUMENT_HASH_PURPOSE = "debtor.document";
    /** Finalidade do blind index da raiz do CNPJ (matriz e filiais). */
    public static final String DOCUMENT_ROOT_HASH_PURPOSE = "debtor.document_root";
    static final int TRADE_NAME_MAX = 200;
    public static final String ANONYMIZED_NAME = "Titular anonimizado";
    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);

    @Enumerated(EnumType.STRING)
    @Column(name = "person_type", nullable = false, length = 20, updatable = false)
    private PersonType personType;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "document_encrypted", length = 512)
    private String document;

    @Column(name = "document_hash", length = 64)
    private String documentHash;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** Nome fantasia (só pessoa jurídica); a razão social é {@link #name}. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "trade_name_encrypted", length = 1024)
    private String tradeName;

    /** Blind index da raiz do CNPJ (finalidade {@link #DOCUMENT_ROOT_HASH_PURPOSE}); nulo para CPF. */
    @Column(name = "document_root_hash", length = 64)
    private String documentRootHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DebtorStatus status;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "anonymized_at")
    private Instant anonymizedAt;

    /** Carga que criou o devedor (nulo se criado à mão ou antes do registro): contato da mesma carga nunca é "novo". */
    @Column(name = "created_import_batch_id", updatable = false)
    private UUID createdImportBatchId;

    protected Debtor() {
    }

    /**
     * @param documentHash blind index de {@code document.number()} com finalidade {@link #DOCUMENT_HASH_PURPOSE}
     */
    public Debtor(Document document, String documentHash, String name, LocalDate birthDate, String notes) {
        this(document, documentHash, null, name, null, birthDate, notes);
    }

    /**
     * @param documentRootHash blind index da raiz do CNPJ (finalidade {@link #DOCUMENT_ROOT_HASH_PURPOSE}); obrigatório
     *                         para CNPJ, nulo para CPF
     * @param tradeName        nome fantasia (só CNPJ; opcional)
     */
    public Debtor(Document document, String documentHash, String documentRootHash, String name, String tradeName,
                  LocalDate birthDate, String notes) {
        Objects.requireNonNull(document, "document");
        Guard.require(documentHash != null && !documentHash.isBlank(), "debtor.document_hash_required",
                "Índice do documento obrigatório");
        this.personType = PersonType.of(document);
        this.document = document.number();
        this.documentHash = documentHash;
        this.status = DebtorStatus.ACTIVE;
        if (personType == PersonType.COMPANY && documentRootHash != null) {
            this.documentRootHash = documentRootHash;
        }
        update(name, tradeName, birthDate, notes);
    }

    /** Alteração cadastral manual (sem nome fantasia: o atual é mantido). */
    public void update(String name, LocalDate birthDate, String notes) {
        update(name, tradeName, birthDate, notes);
    }

    /**
     * Alteração cadastral manual. Pessoa jurídica não tem data de nascimento ({@code debtor.birth_date_company}); nome
     * fantasia só existe para pessoa jurídica ({@code debtor.trade_name_individual}).
     */
    public void update(String name, String tradeName, LocalDate birthDate, String notes) {
        requireActive();
        Guard.require(birthDate == null || personType == PersonType.INDIVIDUAL, "debtor.birth_date_company",
                "Empresa não tem data de nascimento");
        this.name = validName(name);
        this.tradeName = validTradeName(tradeName);
        this.birthDate = validBirthDate(birthDate);
        Guard.require(notes == null || notes.length() <= 2000, "debtor.notes_too_long", "Observações muito longas");
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
    }

    /**
     * Atualização a partir de uma carga do credor: o nome sempre reflete o informado; a data de
     * nascimento só é substituída quando informada. Observações internas não são tocadas.
     *
     * @return true se algum dado mudou
     */
    public boolean updateFromImport(String name, LocalDate birthDate) {
        return updateFromImport(name, null, birthDate);
    }

    /**
     * Como {@link #updateFromImport(String, LocalDate)}, com o nome fantasia (só pessoa jurídica: substituído quando
     * informado). Data de nascimento informada para pessoa jurídica é ignorada (a carga já avisa a linha).
     *
     * @return true se algum dado mudou
     */
    public boolean updateFromImport(String name, String tradeName, LocalDate birthDate) {
        requireActive();
        String newName = validName(name);
        String newTradeName = tradeName == null || tradeName.isBlank() || personType != PersonType.COMPANY
                ? this.tradeName : validTradeName(tradeName);
        LocalDate newBirthDate = birthDate == null || personType != PersonType.INDIVIDUAL ? this.birthDate
                : validBirthDate(birthDate);
        boolean changed = !newName.equals(this.name) || !Objects.equals(newBirthDate, this.birthDate)
                || !Objects.equals(newTradeName, this.tradeName);
        this.name = newName;
        this.tradeName = newTradeName;
        this.birthDate = newBirthDate;
        return changed;
    }

    /**
     * Raiz do CNPJ de cadastros anteriores a ela (preenchida pela varredura do módulo ou na próxima carga).
     *
     * @return true se foi preenchida agora
     */
    public boolean assignDocumentRoot(String rootHash) {
        if (personType != PersonType.COMPANY || status != DebtorStatus.ACTIVE || documentRootHash != null
                || rootHash == null || rootHash.isBlank()) {
            return false;
        }
        this.documentRootHash = rootHash;
        return true;
    }

    /** "Matriz" ou "Filial 0002" pelo sufixo do CNPJ; nulo para pessoa física ou após anonimização. */
    public String establishmentLabel() {
        return document().filter(d -> d.type() == Document.DocumentType.CNPJ)
                .map(d -> d.isHeadOffice() ? "Matriz" : "Filial " + d.cnpjBranch().orElseThrow())
                .orElse(null);
    }

    /**
     * Elimina os dados pessoais do cadastro (LGPD). O registro permanece, sem documento, nome
     * real, nascimento ou observações, porque títulos e lançamentos financeiros o referenciam.
     * Os contatos são eliminados pelo caso de uso (agregado próprio).
     */
    public void anonymize(Instant now) {
        requireActive();
        this.name = ANONYMIZED_NAME;
        this.document = null;
        this.documentHash = null;
        this.birthDate = null;
        this.notes = null;
        this.tradeName = null;
        this.documentRootHash = null;
        this.status = DebtorStatus.ANONYMIZED;
        this.anonymizedAt = now;
    }

    public boolean isAnonymized() {
        return status == DebtorStatus.ANONYMIZED;
    }

    /** Documento validado; vazio após anonimização. */
    public java.util.Optional<Document> document() {
        return document == null ? java.util.Optional.empty() : java.util.Optional.of(Document.parse(document));
    }

    /**
     * Confere a identidade declarada por quem fala pelo devedor (atendimento automatizado) sem
     * revelar o documento: os primeiros dígitos do documento (comparação em tempo constante) e a
     * data de nascimento. Devedor anonimizado, sem data de nascimento ou pessoa jurídica nunca
     * confere (a verificação fica com um humano).
     *
     * @param documentPrefix primeiros 3 a 6 dígitos informados
     */
    public boolean matchesIdentity(String documentPrefix, LocalDate declaredBirthDate) {
        if (document == null || birthDate == null || personType != PersonType.INDIVIDUAL || documentPrefix == null
                || declaredBirthDate == null || !documentPrefix.matches("\\d{3,6}")) {
            return false;
        }
        byte[] expected = document.substring(0, documentPrefix.length()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] declared = documentPrefix.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        boolean documentMatches = java.security.MessageDigest.isEqual(expected, declared);
        return documentMatches & birthDate.equals(declaredBirthDate);
    }

    /** Documento mascarado para exibição; {@code null} após anonimização. */
    public String maskedDocument() {
        return document().map(Document::masked).orElse(null);
    }

    private void requireActive() {
        Guard.requireState(status == DebtorStatus.ACTIVE, "debtor.anonymized", "Devedor anonimizado não pode ser alterado");
    }

    private static String validName(String name) {
        Guard.require(name != null && !name.isBlank(), "debtor.name_required", "Nome do devedor obrigatório");
        String trimmed = name.trim().replaceAll("\\s+", " ");
        Guard.require(trimmed.length() <= 200, "debtor.name_too_long", "Nome do devedor muito longo");
        return trimmed;
    }

    private String validTradeName(String tradeName) {
        if (tradeName == null || tradeName.isBlank()) {
            return null;
        }
        Guard.require(personType == PersonType.COMPANY, "debtor.trade_name_individual",
                "Nome fantasia só existe para pessoa jurídica");
        String trimmed = tradeName.trim().replaceAll("\\s+", " ");
        Guard.require(trimmed.length() <= TRADE_NAME_MAX, "debtor.trade_name_too_long", "Nome fantasia muito longo");
        return trimmed;
    }

    private static LocalDate validBirthDate(LocalDate birthDate) {
        Guard.require(birthDate == null || birthDate.isAfter(MIN_BIRTH_DATE), "debtor.birth_date_invalid",
                "Data de nascimento inválida");
        return birthDate;
    }

    public PersonType getPersonType() {
        return personType;
    }

    public String getName() {
        return name;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getTradeName() {
        return tradeName;
    }

    public String getDocumentRootHash() {
        return documentRootHash;
    }

    public boolean isCompany() {
        return personType == PersonType.COMPANY;
    }

    public DebtorStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getAnonymizedAt() {
        return anonymizedAt;
    }

    /** Registra a carga que criou o devedor (somente na criação por carga). */
    public void createdByImport(UUID importBatchId) {
        Guard.requireState(this.createdImportBatchId == null && getVersion() == null, "debtor.import_origin_immutable",
                "A carga de origem é definida só na criação do devedor");
        this.createdImportBatchId = importBatchId;
    }

    /** O devedor já existia antes da carga informada (criado à mão ou por outra carga). */
    public boolean existedBefore(UUID importBatchId) {
        return importBatchId == null || !importBatchId.equals(createdImportBatchId);
    }

    public UUID getCreatedImportBatchId() {
        return createdImportBatchId;
    }
}
