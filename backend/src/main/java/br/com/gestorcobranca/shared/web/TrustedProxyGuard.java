package br.com.gestorcobranca.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Em produção a aplicação está sempre atrás do ALB: sem {@code app.http.trusted-proxies} todos os
 * clientes pareceriam vir do IP do ALB (limites por IP valendo para todos juntos e trilha sem o IP
 * real). Nesse caso a aplicação não sobe. As faixas são validadas na subida em qualquer perfil.
 */
@Component
class TrustedProxyGuard {

    private static final Logger LOG = LoggerFactory.getLogger(TrustedProxyGuard.class);

    TrustedProxyGuard(HttpProperties properties, Environment environment) {
        properties.trustedProxyRanges();
        if (properties.trustedProxies().isEmpty()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException(
                        "app.http.trusted-proxies (APP_HTTP_TRUSTED_PROXIES) é obrigatório no perfil prod: informe as sub-redes do ALB");
            }
            LOG.info("Nenhum proxy confiável configurado: X-Forwarded-* ignorado, IP do cliente = IP da conexão");
        } else {
            LOG.atInfo().addKeyValue("trustedProxies", String.join(",", properties.trustedProxies()))
                    .log("Proxies confiáveis configurados para X-Forwarded-For/Proto/Port");
        }
    }
}
