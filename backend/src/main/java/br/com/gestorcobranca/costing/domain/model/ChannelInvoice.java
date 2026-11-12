package br.com.gestorcobranca.costing.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Fatura real de um item de custo de canal num mês (desenho §3.5 e §13), informada pela gestão em reais — a fatura de
 * IA em dólar entra convertida pela gestão. Uma por mês × item: informar de novo substitui (auditado). Zero vale (ex.:
 * franquia sem cobrança no mês). Fornecedor e observação opcionais, texto livre da assessoria.
 */
@Entity
@Table(name = "channel_invoice", schema = "costing")
public class ChannelInvoice extends TenantScopedEntity {

    public static final int SUPPLIER_MAX = 120;
    public static final int NOTE_MAX = 500;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");

    @Column(name = "month", nullable = false, updatable = false)
    private LocalDate month;

    @Enumerated(EnumType.STRING)
    @Column(name = "item", nullable = false, length = 20, updatable = false)
    private ChannelItem item;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "supplier", length = SUPPLIER_MAX)
    private String supplier;

    @Column(name = "note", length = NOTE_MAX)
    private String note;

    protected ChannelInvoice() {
    }

    public ChannelInvoice(YearMonth month, ChannelItem item, BigDecimal amount, String supplier, String note) {
        Guard.requireNonNull(month, "costing.invoice_month_required", "Informe o mês da fatura");
        Guard.requireNonNull(item, "costing.invoice_item_required", "Informe o item da fatura");
        this.month = month.atDay(1);
        this.item = item;
        replace(amount, supplier, note);
    }

    /** Substitui o valor, o fornecedor e a observação da fatura do mês. */
    public void replace(BigDecimal value, String supplierName, String observation) {
        Guard.require(value != null && value.signum() >= 0 && value.compareTo(MAX_AMOUNT) <= 0,
                "costing.invoice_amount_invalid", "Informe o valor da fatura (zero ou mais)");
        Guard.require(value.stripTrailingZeros().scale() <= 2, "costing.invoice_amount_scale",
                "O valor da fatura tem no máximo duas casas decimais");
        String s = blankToNull(supplierName);
        String n = blankToNull(observation);
        Guard.require(s == null || s.length() <= SUPPLIER_MAX, "costing.invoice_supplier_too_long",
                "Fornecedor com no máximo " + SUPPLIER_MAX + " caracteres");
        Guard.require(n == null || n.length() <= NOTE_MAX, "costing.invoice_note_too_long",
                "Observação com no máximo " + NOTE_MAX + " caracteres");
        this.amount = value.setScale(2, RoundingMode.UNNECESSARY);
        this.supplier = s;
        this.note = n;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public YearMonth getMonth() {
        return YearMonth.from(month);
    }

    public ChannelItem getItem() {
        return item;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getSupplier() {
        return supplier;
    }

    public String getNote() {
        return note;
    }
}
