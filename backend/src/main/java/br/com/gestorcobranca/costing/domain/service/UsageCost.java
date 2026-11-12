package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.TariffChannel;
import br.com.gestorcobranca.costing.domain.model.Usage.AiUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.CallUsage;
import br.com.gestorcobranca.costing.domain.model.Usage.MessageCount;
import br.com.gestorcobranca.costing.domain.model.Usage.Posted;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Regras de custo de uso (desenho §4): telefonia por minuto tarifado, mensagens por tarifa do canal, IA convertida pelo
 * câmbio do mês e valores já lançados por outros módulos (comissão variável, custas jurídicas). Os custos de canal saem
 * por item faturado ({@link ChannelItem}), base do ajuste pela fatura (§13).
 */
public final class UsageCost {

    private static final String BRL = "BRL";

    private UsageCost() {
    }

    /** Minuto tarifado: arredondado para cima, por chamada atendida (sem conversa = 0). */
    public static long billedMinutes(int talkSeconds) {
        return talkSeconds <= 0 ? 0 : (talkSeconds + 59L) / 60L;
    }

    /** Telefonia: minutos tarifados × tarifa por minuto da conta usada na chamada; conta sem tarifa → zero e aviso. */
    public static void telephony(CallUsage usage, CostAccumulator acc) {
        long minutes = billedMinutes(usage.talkSeconds()) * usage.calls();
        if (minutes == 0) {
            return;
        }
        BigDecimal quantity = BigDecimal.valueOf(minutes);
        BigDecimal amount = usage.ratePerMinute() == null ? BigDecimal.ZERO : quantity.multiply(usage.ratePerMinute());
        acc.add(usage.day(), usage.creditorId(), usage.portfolioId(), CostCategory.TELEPHONY, amount, quantity,
                CostFact.Unit.MINUTES, ChannelItem.TELEPHONY);
        if (usage.ratePerMinute() == null) {
            acc.warn(usage.day(), usage.creditorId(), usage.portfolioId(), CostWarningCode.MISSING_TARIFF, quantity);
        }
    }

    /** Mensagens (SMS, e-mail) cobradas por mensagem saída. */
    public static void messages(MessageCount count, RateBook rates, CostAccumulator acc) {
        billUnits(count.day(), count.creditorId(), count.portfolioId(), count.channel(), count.messages(), rates, acc);
    }

    /** Conversas de WhatsApp iniciadas pela empresa (uma por janela de 24 h por conversa e categoria). */
    public static void whatsAppConversation(LocalDate day, UUID creditorId, UUID portfolioId, TariffChannel channel,
                                            RateBook rates, CostAccumulator acc) {
        billUnits(day, creditorId, portfolioId, channel, 1, rates, acc);
    }

    private static void billUnits(LocalDate day, UUID creditorId, UUID portfolioId, TariffChannel channel, long units,
                                  RateBook rates, CostAccumulator acc) {
        if (units <= 0) {
            return;
        }
        BigDecimal quantity = BigDecimal.valueOf(units);
        var tariff = rates.tariff(channel, day);
        acc.add(day, creditorId, portfolioId, CostCategory.MESSAGING, tariff.map(quantity::multiply).orElse(BigDecimal.ZERO),
                quantity, CostFact.Unit.UNITS, ChannelItem.of(channel));
        if (tariff.isEmpty()) {
            acc.warn(day, creditorId, portfolioId, CostWarningCode.MISSING_TARIFF, quantity);
        }
    }

    /**
     * IA: valor em dólar × câmbio do mês (sem câmbio do mês, o último anterior com aviso {@code MISSING_FX_RATE}; sem
     * nenhum, zero com aviso {@code NO_FX_RATE}). Valor já em real ({@code BRL}) entra como veio. Moeda nula é tratada
     * como dólar (a tabela de preços do provedor de IA é em dólar). A quantidade do fato é o valor em dólar.
     */
    public static void ai(AiUsage usage, RateBook rates, CostAccumulator acc) {
        if (usage.amount() == null || usage.amount().signum() == 0) {
            return;
        }
        if (BRL.equalsIgnoreCase(usage.currency())) {
            acc.add(usage.day(), usage.creditorId(), usage.portfolioId(), CostCategory.AI, usage.amount(),
                    BigDecimal.ZERO, CostFact.Unit.USD, ChannelItem.AI);
            return;
        }
        RateBook.FxResolution fx = rates.fx(YearMonth.from(usage.day()));
        acc.add(usage.day(), usage.creditorId(), usage.portfolioId(), CostCategory.AI, usage.amount().multiply(fx.rate()),
                usage.amount(), CostFact.Unit.USD, ChannelItem.AI);
        switch (fx.status()) {
            case FALLBACK -> acc.warn(usage.day(), usage.creditorId(), usage.portfolioId(),
                    CostWarningCode.MISSING_FX_RATE, usage.amount());
            case NONE -> acc.warn(usage.day(), usage.creditorId(), usage.portfolioId(), CostWarningCode.NO_FX_RATE,
                    usage.amount());
            case OWN -> {
                // câmbio do mês: sem aviso
            }
        }
    }

    /** Valor já em real lançado por outro módulo (comissão variável, custas jurídicas da assessoria). */
    public static void posted(Posted posted, CostCategory category, CostAccumulator acc) {
        if (posted.amount() == null || (posted.amount().signum() == 0 && posted.entries() == 0)) {
            return;
        }
        acc.add(posted.day(), posted.creditorId(), posted.portfolioId(), category, posted.amount(),
                BigDecimal.valueOf(posted.entries()), CostFact.Unit.ENTRIES);
    }
}
