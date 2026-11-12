package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.ChannelTariff;
import br.com.gestorcobranca.costing.domain.model.CostBand;
import br.com.gestorcobranca.costing.domain.model.FxRate;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.costing.domain.model.UserCostBand;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cadastros vigentes usados na apuração: valor-hora da pessoa no dia (faixa da pessoa no dia → vigência da faixa com
 * esse nome no dia), tarifa do canal no dia e câmbio do mês.
 */
public final class RateBook {

    /** Situação do câmbio usado num mês. */
    public enum FxStatus {
        /** Câmbio informado para o mês. */
        OWN,
        /** Sem câmbio do mês: usado o último câmbio anterior informado (aviso {@code MISSING_FX_RATE}). */
        FALLBACK,
        /** Nenhum câmbio informado até o mês: custo de IA em zero (aviso {@code NO_FX_RATE}). */
        NONE
    }

    public record FxResolution(BigDecimal rate, FxStatus status) {
    }

    private final Map<UUID, List<UserCostBand>> userBands;
    private final Map<String, List<CostBand>> bands;
    private final Map<TariffChannel, List<ChannelTariff>> tariffs;
    private final TreeMap<YearMonth, BigDecimal> fx = new TreeMap<>();

    public RateBook(Collection<CostBand> bands, Collection<UserCostBand> userBands, Collection<ChannelTariff> tariffs,
                    Collection<FxRate> fxRates) {
        this.bands = bands.stream().collect(Collectors.groupingBy(CostBand::getName));
        this.userBands = userBands.stream().collect(Collectors.groupingBy(UserCostBand::getUserId));
        this.tariffs = tariffs.stream().collect(Collectors.groupingBy(ChannelTariff::getChannel));
        fxRates.forEach(r -> fx.put(r.getMonth(), r.getUsdBrl()));
    }

    /** Custo-empresa por hora da pessoa no dia; vazio = sem faixa vigente (horas com custo zero e aviso). */
    public Optional<BigDecimal> hourlyCost(UUID userId, LocalDate day) {
        return userBands.getOrDefault(userId, List.of()).stream().filter(b -> b.covers(day)).findFirst()
                .flatMap(ub -> bands.getOrDefault(ub.getBandName(), List.of()).stream().filter(b -> b.covers(day))
                        .findFirst())
                .map(CostBand::getHourlyCost);
    }

    /** Tarifa do canal no dia; vazio = sem tarifa vigente (custo zero e aviso). */
    public Optional<BigDecimal> tariff(TariffChannel channel, LocalDate day) {
        return tariffs.getOrDefault(channel, List.of()).stream().filter(t -> t.covers(day)).findFirst()
                .map(ChannelTariff::getUnitCost);
    }

    /** Câmbio do mês; sem ele, o último anterior; sem nenhum, zero. */
    public FxResolution fx(YearMonth month) {
        BigDecimal own = fx.get(month);
        if (own != null) {
            return new FxResolution(own, FxStatus.OWN);
        }
        Map.Entry<YearMonth, BigDecimal> previous = fx.lowerEntry(month);
        if (previous != null) {
            return new FxResolution(previous.getValue(), FxStatus.FALLBACK);
        }
        return new FxResolution(BigDecimal.ZERO, FxStatus.NONE);
    }
}
