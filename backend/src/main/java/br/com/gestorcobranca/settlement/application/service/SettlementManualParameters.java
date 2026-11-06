package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.SettlementProperties;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Valor (absoluto) acima do qual o ajuste do demonstrativo exige a aprovação de outra pessoa. */
@Component
class SettlementManualParameters implements ManualParameterSource {

    private final SettlementProperties properties;

    SettlementManualParameters(SettlementProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "settlement";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        return Map.of("adjustmentDualApprovalThreshold", properties.adjustmentDualApprovalThreshold());
    }
}
