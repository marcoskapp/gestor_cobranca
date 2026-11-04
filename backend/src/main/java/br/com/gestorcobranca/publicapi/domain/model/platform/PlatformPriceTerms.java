package br.com.gestorcobranca.publicapi.domain.model.platform;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Condições de uma versão da política de cobrança da plataforma para uma assessoria, independentes das condições que a
 * assessoria cobra dos credores: dia de fechamento do ciclo (1–31), mensalidade, franquias e preço do excedente por
 * chamada (preço único ou escalonado por faixas de volume), por MB recebido, por MB enviado e por registro processado.
 * Valores em BRL; mensalidade com até 2 casas e preços unitários com até 6.
 *
 * @param pricePerRequest preço único por chamada excedente; {@code null} quando há faixas de volume
 * @param requestTiers    faixas de volume das chamadas, em ordem crescente (vazio = preço único); até
 *                        {@value #MAX_TIERS}, a última sem limite
 */
public record PlatformPriceTerms(int closingDay, BigDecimal monthlyFee, long includedRequests, BigDecimal pricePerRequest,
                                 List<VolumeTier> requestTiers, long includedMbIn, BigDecimal pricePerMbIn,
                                 long includedMbOut, BigDecimal pricePerMbOut, long includedRecords,
                                 BigDecimal pricePerRecord) {

    public static final int MAX_TIERS = 10;

    public PlatformPriceTerms {
        Guard.require(closingDay >= 1 && closingDay <= 31, "platform_policy.closing_day_invalid",
                "Dia de fechamento deve estar entre 1 e 31");
        monthlyFee = money(monthlyFee, "mensalidade", 2);
        includedRequests = allowance(includedRequests);
        includedMbIn = allowance(includedMbIn);
        includedMbOut = allowance(includedMbOut);
        includedRecords = allowance(includedRecords);
        pricePerMbIn = money(pricePerMbIn, "preço por MB recebido", 6);
        pricePerMbOut = money(pricePerMbOut, "preço por MB enviado", 6);
        pricePerRecord = money(pricePerRecord, "preço por registro", 6);
        requestTiers = tiers(requestTiers);
        if (requestTiers.isEmpty()) {
            pricePerRequest = money(pricePerRequest, "preço por chamada", 6);
        } else {
            Guard.require(pricePerRequest == null, "platform_policy.request_price_ambiguous",
                    "Com faixas de volume, o preço por chamada vem das faixas");
        }
    }

    public boolean tieredRequests() {
        return !requestTiers.isEmpty();
    }

    private static List<VolumeTier> tiers(List<VolumeTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return List.of();
        }
        Guard.require(tiers.size() <= MAX_TIERS, "platform_policy.tiers_too_many",
                "No máximo %d faixas de volume".formatted(MAX_TIERS));
        List<VolumeTier> normalized = new ArrayList<>();
        long previous = 0;
        for (int i = 0; i < tiers.size(); i++) {
            VolumeTier t = tiers.get(i);
            Guard.require(t != null, "platform_policy.tier_invalid", "Faixa de volume inválida");
            boolean last = i == tiers.size() - 1;
            if (last) {
                Guard.require(t.upTo() == null, "platform_policy.tier_last_unbounded",
                        "A última faixa de volume não tem limite");
            } else {
                Guard.require(t.upTo() != null && t.upTo() > previous, "platform_policy.tier_order_invalid",
                        "Os limites das faixas de volume devem ser crescentes e positivos");
                previous = t.upTo();
            }
            normalized.add(new VolumeTier(t.upTo(), money(t.unitPrice(), "preço da faixa " + (i + 1), 6)));
        }
        return List.copyOf(normalized);
    }

    private static BigDecimal money(BigDecimal value, String field, int scale) {
        Guard.require(value != null && value.signum() >= 0, "platform_policy.value_invalid",
                "Valor de %s deve ser zero ou positivo".formatted(field));
        Guard.require(value.stripTrailingZeros().scale() <= scale, "platform_policy.scale_invalid",
                "%s com no máximo %d casas decimais".formatted(field, scale));
        Guard.require(value.precision() - value.scale() <= 13, "platform_policy.value_too_large",
                "Valor muito alto: " + field);
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    private static long allowance(long value) {
        Guard.require(value >= 0, "platform_policy.allowance_invalid", "Franquias devem ser zero ou positivas");
        return value;
    }
}
