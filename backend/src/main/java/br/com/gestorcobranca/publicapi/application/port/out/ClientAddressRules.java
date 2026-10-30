package br.com.gestorcobranca.publicapi.application.port.out;

import java.util.Collection;
import java.util.List;

/** Validação de faixas de IP (CIDR) e conferência do IP do cliente contra a lista permitida de um token. */
public interface ClientAddressRules {

    /**
     * Normaliza e valida (CIDR ou IP literal); recusa faixas amplas demais (prefixo menor que /8 no IPv4 ou /32 no
     * IPv6) com {@code integration_token.allowed_ip_invalid} ou {@code integration_token.allowed_ip_too_wide}.
     */
    List<String> normalize(Collection<String> values);

    /** Sem lista = qualquer origem; com lista, IP ausente ou inválido é recusado. */
    boolean allows(Collection<String> allowed, String clientIp);
}
