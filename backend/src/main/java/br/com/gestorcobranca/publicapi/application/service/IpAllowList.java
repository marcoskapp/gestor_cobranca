package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.web.CidrRange;
import br.com.gestorcobranca.shared.web.ClientAddresses;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Validação e conferência das faixas de IP permitidas de um token. */
final class IpAllowList {

    private IpAllowList() {
    }

    /** Normaliza e valida (CIDR ou IP literal); recusa faixas amplas demais (prefixo menor que /8 ou /32 no IPv6). */
    static List<String> normalize(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.strip();
            CidrRange range;
            try {
                range = CidrRange.parse(value);
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleException("integration_token.allowed_ip_invalid", "Faixa de IP inválida: " + value);
            }
            int bits = range.network().length * 8;
            int minPrefix = bits == 32 ? 8 : 32;
            if (range.prefix() < minPrefix) {
                throw new BusinessRuleException("integration_token.allowed_ip_too_wide",
                        "Faixa de IP ampla demais: " + value);
            }
            result.add(value.contains("/") ? value : value + "/" + bits);
        }
        return List.copyOf(result);
    }

    /** Sem lista = qualquer origem; com lista, IP ausente ou inválido é recusado. */
    static boolean allows(Collection<String> allowed, String clientIp) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (clientIp == null || !ClientAddresses.isValid(clientIp)) {
            return false;
        }
        String literal = ClientAddresses.parse(clientIp).orElse(null);
        return literal != null && allowed.stream().anyMatch(c -> CidrRange.parse(c).contains(literal));
    }
}
