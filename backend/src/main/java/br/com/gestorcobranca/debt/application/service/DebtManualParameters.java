package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Prazos da correção de valor citados no manual, no tour e nas novidades: a janela do limite acumulado por título e a
 * validade da proposta de aumento do limite (em dias inteiros, garantidos por {@link DebtProperties}).
 */
@Component
class DebtManualParameters implements ManualParameterSource {

    private final DebtProperties properties;

    DebtManualParameters(DebtProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "debt";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        DebtProperties.AmountAdjustment a = properties.amountAdjustment();
        return Map.of("cumulativeWindowDays", BigDecimal.valueOf(a.cumulativeWindow().toDays()),
                "proposalValidityDays", BigDecimal.valueOf(a.proposalValidity().toDays()));
    }
}
