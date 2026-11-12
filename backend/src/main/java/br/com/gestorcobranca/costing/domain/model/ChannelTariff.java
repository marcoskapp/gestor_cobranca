package br.com.gestorcobranca.costing.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Tarifa de um canal de mensagem com vigência: R$ por unidade cobrada (conversa de WhatsApp por categoria, mensagem de
 * SMS ou de e-mail). Zero é válido (ex.: e-mail desprezível). A telefonia usa a tarifa por minuto da conta do provedor
 * ({@code telephony.provider_account.rate_per_minute}).
 */
@Entity
@Table(name = "channel_tariff", schema = "costing")
public class ChannelTariff extends Dated {

    public static final BigDecimal MAX_UNIT_COST = new BigDecimal("999999.999999");

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 30, updatable = false)
    private TariffChannel channel;

    @Column(name = "unit_cost", nullable = false, updatable = false)
    private BigDecimal unitCost;

    protected ChannelTariff() {
    }

    public ChannelTariff(TariffChannel channel, BigDecimal unitCost, LocalDate validFrom) {
        super(validFrom);
        this.channel = Guard.requireNonNull(channel, "costing.tariff_channel_required", "Informe o canal");
        Guard.require(unitCost != null && unitCost.signum() >= 0 && unitCost.compareTo(MAX_UNIT_COST) <= 0,
                "costing.tariff_cost_invalid", "Informe uma tarifa igual ou maior que zero");
        Guard.require(unitCost.stripTrailingZeros().scale() <= 6, "costing.tariff_cost_scale",
                "A tarifa tem no máximo seis casas decimais");
        this.unitCost = unitCost.setScale(6, RoundingMode.UNNECESSARY);
    }

    public TariffChannel getChannel() {
        return channel;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
