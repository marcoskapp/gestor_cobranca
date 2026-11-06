package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.ScoringProperties;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Padrões do score citados no manual: janela de desfecho e carência da apuração, AUC mínima e limites de PSI. São os
 * parâmetros iniciais de toda assessoria (cada uma pode alterar os seus nos parâmetros do score); o manual os
 * apresenta como "padrão".
 */
@Component
class ScoringManualParameters implements ManualParameterSource {

    private final ScoringProperties properties;

    ScoringManualParameters(ScoringProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "scoring";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        ScoringSettings.Parameters defaults = properties.defaults().toParameters();
        return Map.of("outcomeDays", BigDecimal.valueOf(defaults.outcomeDays()),
                "outcomeGraceDays", BigDecimal.valueOf(properties.outcomeGraceDays()),
                "minAuc", defaults.minAuc(),
                "psiWarning", defaults.psiWarning(),
                "psiAlert", defaults.psiAlert());
    }
}
