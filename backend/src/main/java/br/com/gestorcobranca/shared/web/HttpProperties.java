package br.com.gestorcobranca.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Parâmetros da borda HTTP ({@code app.http.*}).
 *
 * @param trustedProxies faixas CIDR dos proxies confiáveis imediatamente à frente da aplicação (em
 *                       produção, as sub-redes do ALB). Só requisições cuja conexão vem de uma dessas
 *                       faixas têm {@code X-Forwarded-For}/{@code -Proto}/{@code -Port} considerados;
 *                       vazio = nenhum proxy (o IP é o da conexão).
 */
@ConfigurationProperties(prefix = "app.http")
public record HttpProperties(List<String> trustedProxies) {

    public HttpProperties {
        trustedProxies = trustedProxies == null ? List.of()
                : trustedProxies.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    }

    public List<CidrRange> trustedProxyRanges() {
        return trustedProxies.stream().map(CidrRange::parse).toList();
    }
}
