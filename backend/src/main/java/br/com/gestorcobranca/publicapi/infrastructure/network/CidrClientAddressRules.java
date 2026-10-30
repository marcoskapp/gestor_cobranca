package br.com.gestorcobranca.publicapi.infrastructure.network;

import br.com.gestorcobranca.publicapi.application.port.out.ClientAddressRules;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.web.CidrRange;
import br.com.gestorcobranca.shared.web.ClientAddresses;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Faixas CIDR com as mesmas regras de interpretação de IP do restante da plataforma ({@code shared.web}). */
@Component
class CidrClientAddressRules implements ClientAddressRules {

    @Override
    public List<String> normalize(Collection<String> values) {
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
                throw new BusinessRuleException("integration_token.allowed_ip_too_wide", "Faixa de IP ampla demais: " + value);
            }
            result.add(value.contains("/") ? value : value + "/" + bits);
        }
        return List.copyOf(result);
    }

    @Override
    public boolean allows(Collection<String> allowed, String clientIp) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        String literal = ClientAddresses.parse(clientIp).orElse(null);
        return literal != null && allowed.stream().anyMatch(c -> CidrRange.parse(c).contains(literal));
    }
}
