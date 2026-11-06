package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Tempo sem sinal do navegador até o operador sair da telefonia (em segundos inteiros, garantidos por
 * {@link TelephonyProperties}).
 */
@Component
class TelephonyManualParameters implements ManualParameterSource {

    private final TelephonyProperties properties;

    TelephonyManualParameters(TelephonyProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "telephony";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        return Map.of("presenceTimeoutSeconds", BigDecimal.valueOf(properties.presenceTimeout().toSeconds()));
    }
}
