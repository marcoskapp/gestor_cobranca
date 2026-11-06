package br.com.gestorcobranca.pendingwork.application.service;

import br.com.gestorcobranca.pendingwork.application.PendingWorkProperties;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Janela de "perto do prazo" das pendências que só aparecem quando o vencimento se aproxima (dias corridos). */
@Component
class PendingWorkManualParameters implements ManualParameterSource {

    private final PendingWorkProperties properties;

    PendingWorkManualParameters(PendingWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "pendingwork";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        return Map.of("dueSoonDays", BigDecimal.valueOf(properties.dueSoonDays()));
    }
}
