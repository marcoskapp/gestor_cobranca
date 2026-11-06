package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.BreakSuspensionProperties;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Suspensão da quebra do acordo com pagamento informado em revisão: prazo máximo e carência após a recusa (dias). */
@Component
class NegotiationManualParameters implements ManualParameterSource {

    private final BreakSuspensionProperties properties;

    NegotiationManualParameters(BreakSuspensionProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "negotiation";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        return Map.of("breakSuspensionMaxDays", BigDecimal.valueOf(properties.maxDays()),
                "breakSuspensionGraceDays", BigDecimal.valueOf(properties.graceDays()));
    }
}
