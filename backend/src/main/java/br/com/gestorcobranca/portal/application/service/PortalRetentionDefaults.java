package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults;
import br.com.gestorcobranca.portal.application.PortalProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Prazos padrão do sistema da retenção dos dados de acesso ao portal ({@code app.portal.retention}), informados ao
 * compliance: a tela de Retenção os exibe e o job {@code portal.retention} os usa enquanto a assessoria não configura
 * o próprio prazo.
 */
@Component
class PortalRetentionDefaults implements RetentionDefaults {

    static final String CHALLENGES = "PORTAL_ACCESS_CHALLENGES";
    static final String THROTTLES = "PORTAL_ACCESS_THROTTLES";
    static final String SESSION_CLIENT_DATA = "PORTAL_SESSION_CLIENT_DATA";

    private final PortalProperties properties;

    PortalRetentionDefaults(PortalProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<RetentionDefault> retentionDefaults() {
        PortalProperties.Retention r = properties.retention();
        return List.of(new RetentionDefault(CHALLENGES, days(r.challenges(), "challenges")),
                new RetentionDefault(THROTTLES, days(r.throttles(), "throttles")),
                new RetentionDefault(SESSION_CLIENT_DATA, days(r.sessionClientData(), "session-client-data")));
    }

    /** O prazo da tela é em dias inteiros: fração de dia na configuração é erro (a subida falha). */
    private static int days(Duration duration, String property) {
        if (duration.isNegative() || !duration.equals(Duration.ofDays(duration.toDays()))) {
            throw new IllegalStateException("app.portal.retention.%s deve ser em dias inteiros: %s".formatted(property,
                    duration));
        }
        return Math.toIntExact(duration.toDays());
    }
}
