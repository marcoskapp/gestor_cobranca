package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.CnjNumber;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Recuperação judicial, recuperação extrajudicial ou falência do devedor pessoa jurídica (Lei 11.101/2005; cobrança
 * B2B §7). Enquanto {@link InsolvencyStatus#ACTIVE}, a cobrança do devedor fica suspensa (casos inelegíveis no
 * collection) e o caminho é o jurídico (habilitação do crédito, plano). No máximo um processo ativo por devedor.
 * <p>
 * O registro não apaga interação nem cancela acordo em vigor: a dívida pode estar no plano de recuperação, e a decisão
 * sobre o acordo é humana.
 */
@Entity
@Table(name = "insolvency_proceeding", schema = "debt")
public class InsolvencyProceeding extends TenantScopedEntity {

    public static final int MAX_TEXT = 500;
    public static final int MAX_COURT = 150;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private InsolvencyKind kind;

    /** Número CNJ, 20 dígitos sem máscara. */
    @Column(name = "case_number", nullable = false, length = 20, updatable = false)
    private String caseNumber;

    @Column(name = "court", length = MAX_COURT)
    private String court;

    @Column(name = "filed_on", nullable = false, updatable = false)
    private LocalDate filedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "converted_from", length = 30)
    private InsolvencyKind convertedFrom;

    @Column(name = "converted_on")
    private LocalDate convertedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private InsolvencyStatus status;

    @Column(name = "ended_on")
    private LocalDate endedOn;

    @Column(name = "end_reason", length = MAX_TEXT)
    private String endReason;

    @Column(name = "note", length = MAX_TEXT)
    private String note;

    protected InsolvencyProceeding() {
    }

    /**
     * @param filedOn data do pedido (distribuição): não pode ser futura e é o ano do número CNJ
     * @param today   hoje no fuso de negócio
     */
    public InsolvencyProceeding(UUID debtorId, InsolvencyKind kind, CnjNumber caseNumber, String court,
                                LocalDate filedOn, String note, LocalDate today) {
        Guard.require(debtorId != null, "insolvency.debtor_required", "Devedor obrigatório");
        Guard.require(kind != null, "insolvency.kind_required", "Informe o tipo do processo");
        Guard.require(caseNumber != null, "insolvency.cnj_required", "Número CNJ obrigatório");
        Guard.require(filedOn != null, "insolvency.filed_on_required", "Informe a data do pedido");
        Guard.require(!filedOn.isAfter(today), "insolvency.filed_on_future", "A data do pedido não pode ser futura");
        Guard.require(caseNumber.year() == filedOn.getYear(), "insolvency.cnj_year_mismatch",
                "O ano do número CNJ deve ser o ano do pedido");
        this.debtorId = debtorId;
        this.kind = kind;
        this.caseNumber = caseNumber.digits();
        this.court = text(court, MAX_COURT, "insolvency.court_too_long", "Vara ou juízo");
        this.filedOn = filedOn;
        this.note = text(note, MAX_TEXT, "insolvency.note_too_long", "Nota");
        this.status = InsolvencyStatus.ACTIVE;
    }

    /**
     * Convolação da recuperação judicial em falência (Lei 11.101/2005, art. 73): o mesmo processo muda de tipo.
     *
     * @return tipo anterior
     */
    public InsolvencyKind convertToBankruptcy(LocalDate on, LocalDate today) {
        Guard.requireState(isActive(), "insolvency.not_active", "O processo já foi encerrado");
        Guard.requireState(kind == InsolvencyKind.JUDICIAL_RECOVERY, "insolvency.conversion_not_allowed",
                "Só a recuperação judicial pode ser convolada em falência");
        Guard.require(on != null, "insolvency.converted_on_required", "Informe a data da decretação da falência");
        Guard.require(!on.isBefore(filedOn) && !on.isAfter(today), "insolvency.converted_on_invalid",
                "A decretação deve ser entre a data do pedido e hoje");
        InsolvencyKind previous = kind;
        this.convertedFrom = previous;
        this.convertedOn = on;
        this.kind = InsolvencyKind.BANKRUPTCY;
        return previous;
    }

    /** Encerra o processo (plano cumprido, extinção, registro por engano): a elegibilidade volta a ser avaliada. */
    public void end(LocalDate on, String reason, LocalDate today) {
        Guard.requireState(isActive(), "insolvency.not_active", "O processo já foi encerrado");
        Guard.require(on != null, "insolvency.ended_on_required", "Informe a data do encerramento");
        LocalDate start = convertedOn != null ? convertedOn : filedOn;
        Guard.require(!on.isBefore(start) && !on.isAfter(today), "insolvency.ended_on_invalid",
                "O encerramento deve ser entre o início do processo e hoje");
        Guard.require(reason != null && !reason.isBlank(), "insolvency.end_reason_required",
                "Informe o motivo do encerramento");
        this.endReason = text(reason, MAX_TEXT, "insolvency.end_reason_too_long", "Motivo");
        this.endedOn = on;
        this.status = InsolvencyStatus.ENDED;
    }

    /** Anonimização do devedor (LGPD): texto livre pode citar pessoas; o fato do processo (público) permanece. */
    public void forgetFreeText() {
        this.note = null;
        if (endReason != null) {
            this.endReason = "—";
        }
    }

    public boolean isActive() {
        return status == InsolvencyStatus.ACTIVE;
    }

    /** Início do efeito atual: a decretação da falência, quando convolada; senão, a data do pedido. */
    public LocalDate since() {
        return convertedOn != null ? convertedOn : filedOn;
    }

    public CnjNumber cnj() {
        return new CnjNumber(caseNumber);
    }

    private static String text(String value, int max, String code, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Guard.require(trimmed.length() <= max, code, "%s com no máximo %d caracteres".formatted(label, max));
        return trimmed;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public InsolvencyKind getKind() {
        return kind;
    }

    public String getCaseNumber() {
        return caseNumber;
    }

    public String getCourt() {
        return court;
    }

    public LocalDate getFiledOn() {
        return filedOn;
    }

    public InsolvencyKind getConvertedFrom() {
        return convertedFrom;
    }

    public LocalDate getConvertedOn() {
        return convertedOn;
    }

    public InsolvencyStatus getStatus() {
        return status;
    }

    public LocalDate getEndedOn() {
        return endedOn;
    }

    public String getEndReason() {
        return endReason;
    }

    public String getNote() {
        return note;
    }
}
