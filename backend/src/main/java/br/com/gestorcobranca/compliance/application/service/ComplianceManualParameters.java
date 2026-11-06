package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.ComplianceProperties;
import br.com.gestorcobranca.shared.manual.ManualParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Prazo padrão de atendimento das solicitações de titulares (dias corridos; tipos com prazo próprio ficam à parte). */
@Component
class ComplianceManualParameters implements ManualParameterSource {

    private final ComplianceProperties properties;

    ComplianceManualParameters(ComplianceProperties properties) {
        this.properties = properties;
    }

    @Override
    public String module() {
        return "compliance";
    }

    @Override
    public Map<String, BigDecimal> parameters() {
        return Map.of("dataSubjectRequestDueDays",
                BigDecimal.valueOf(properties.dataSubjectRequest().defaultDueDays()));
    }
}
