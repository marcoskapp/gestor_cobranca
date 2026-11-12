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
import java.util.UUID;

/**
 * Custo fixo ou indireto de um mês (licenças, infraestrutura, aluguel, gestão...) com a regra de rateio (desenho
 * §3.4). Com {@link OverheadAllocation#CREDITOR} o credor é obrigatório; nas demais regras não há credor.
 */
@Entity
@Table(name = "overhead", schema = "costing")
public class Overhead extends TenantScopedEntity {

    public static final int DESCRIPTION_MAX = 200;
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");

    @Column(name = "month", nullable = false, updatable = false)
    private LocalDate month;

    @Column(name = "description", nullable = false, length = DESCRIPTION_MAX, updatable = false)
    private String description;

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation", nullable = false, length = 20, updatable = false)
    private OverheadAllocation allocation;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    protected Overhead() {
    }

    public Overhead(YearMonth month, String description, BigDecimal amount, OverheadAllocation allocation,
                    UUID creditorId) {
        Guard.requireNonNull(month, "costing.overhead_month_required", "Informe o mês");
        Guard.require(description != null && !description.isBlank(), "costing.overhead_description_required",
                "Informe a descrição do custo");
        Guard.require(description.trim().length() <= DESCRIPTION_MAX, "costing.overhead_description_too_long",
                "Descrição com no máximo " + DESCRIPTION_MAX + " caracteres");
        Guard.require(amount != null && amount.signum() > 0 && amount.compareTo(MAX_AMOUNT) <= 0,
                "costing.overhead_amount_invalid", "Informe um valor maior que zero");
        Guard.require(amount.stripTrailingZeros().scale() <= 2, "costing.overhead_amount_scale",
                "O valor tem no máximo duas casas decimais");
        OverheadAllocation rule = allocation == null ? OverheadAllocation.HOURS : allocation;
        Guard.require((rule == OverheadAllocation.CREDITOR) == (creditorId != null), "costing.overhead_creditor",
                rule == OverheadAllocation.CREDITOR ? "Informe o credor do custo direto"
                        : "Credor só com a regra de rateio direto a um credor");
        this.month = month.atDay(1);
        this.description = description.trim();
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        this.allocation = rule;
        this.creditorId = creditorId;
    }

    public YearMonth getMonth() {
        return YearMonth.from(month);
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OverheadAllocation getAllocation() {
        return allocation;
    }

    public UUID getCreditorId() {
        return creditorId;
    }
}
