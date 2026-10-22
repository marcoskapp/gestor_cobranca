package br.com.gestorcobranca.shared.security;

import org.springframework.http.HttpMethod;

/**
 * Endpoint que dispensa o JWT de usuário (login, webhooks de provedores, portal do devedor).
 * Cada módulo declara os seus como beans; a cadeia de segurança (módulo identity) os reúne.
 * <p>
 * Endpoint público não significa sem proteção: webhooks validam assinatura/segredo do
 * provedor, e o portal usa autenticação própria do devedor.
 *
 * @param method  método HTTP (null = todos)
 * @param pattern padrão de caminho Ant/PathPattern, ex.: {@code /api/v1/webhooks/payments/**}
 * @param reason  justificativa documental (aparece no log de inicialização)
 */
public record PublicEndpoint(HttpMethod method, String pattern, String reason) {
}
