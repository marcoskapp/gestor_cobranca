package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarning;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Soma os custos e os avisos por dia × credor × carteira × categoria × item de canal (ou código do aviso). Os valores são somados com
 * precisão total e arredondados só na saída: valor com 6 casas e quantidade com 4 (colunas de {@code cost_daily}).
 */
public final class CostAccumulator {

    private record FactKey(CostKey key, CostCategory category, ChannelItem item) {
    }

    private record WarningKey(CostKey key, CostWarningCode code) {
    }

    private static final class Sum {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        CostFact.Unit unit;
    }

    private final Map<FactKey, Sum> facts = new LinkedHashMap<>();
    private final Map<WarningKey, BigDecimal> warnings = new LinkedHashMap<>();

    public void add(LocalDate day, UUID creditorId, UUID portfolioId, CostCategory category, BigDecimal amount,
                    BigDecimal quantity, CostFact.Unit unit) {
        add(day, creditorId, portfolioId, category, amount, quantity, unit, null);
    }

    /** Custo de um item de canal faturado (telefonia, WhatsApp, SMS, e-mail, IA): somado por item dentro da categoria. */
    public void add(LocalDate day, UUID creditorId, UUID portfolioId, CostCategory category, BigDecimal amount,
                    BigDecimal quantity, CostFact.Unit unit, ChannelItem item) {
        if (item != null && item.category() != category) {
            throw new IllegalArgumentException("Item " + item + " fora da categoria " + category);
        }
        Sum sum = facts.computeIfAbsent(new FactKey(new CostKey(day, creditorId, creditorId == null ? null : portfolioId),
                category, item), k -> new Sum());
        sum.amount = sum.amount.add(amount);
        sum.quantity = sum.quantity.add(quantity);
        if (sum.unit == null) {
            sum.unit = unit;
        } else if (sum.unit != unit) {
            throw new IllegalArgumentException("Unidades diferentes na mesma categoria: " + sum.unit + " e " + unit);
        }
    }

    public void warn(LocalDate day, UUID creditorId, UUID portfolioId, CostWarningCode code, BigDecimal quantity) {
        if (quantity.signum() == 0) {
            return;
        }
        warnings.merge(new WarningKey(new CostKey(day, creditorId, creditorId == null ? null : portfolioId), code),
                quantity, BigDecimal::add);
    }

    public List<CostFact> facts() {
        List<CostFact> out = new ArrayList<>(facts.size());
        facts.forEach((k, s) -> out.add(new CostFact(k.key().day(), k.key().creditorId(), k.key().portfolioId(),
                k.category(), s.amount.setScale(6, RoundingMode.HALF_EVEN), s.quantity.setScale(4, RoundingMode.HALF_EVEN),
                s.unit, k.item())));
        return out;
    }

    public List<CostWarning> warnings() {
        List<CostWarning> out = new ArrayList<>(warnings.size());
        warnings.forEach((k, q) -> out.add(new CostWarning(k.key().day(), k.key().creditorId(), k.key().portfolioId(),
                k.code(), q.setScale(4, RoundingMode.HALF_EVEN))));
        return out;
    }
}
