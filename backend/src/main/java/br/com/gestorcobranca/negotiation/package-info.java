/**
 * Negociação (M06, F08, F09, F16, RB03–RB05) — ver docs/tecnico/desenho/negociacao-e-alcadas.md.
 * <p>
 * Parte 1 (este estado): políticas de cobrança versionadas por credor com sobreposições por
 * carteira e faixa de atraso, tabela manual de índices de correção, motor de cálculo puro com
 * memória de cálculo, envelope de concessões e escada de níveis, perfis de alçada (humanos e
 * assistente) com atribuição por usuário/credor, simulação persistida e {@code NegotiationGuard}
 * — a validação única pela qual passam humanos e o assistente.
 * <p>
 * Dependências: {@code organization} (credores e carteiras), {@code identity} (usuários para a
 * atribuição de alçada) e {@code debt} (títulos e saldos por componente). A dependência de
 * {@code collection} prevista no desenho entra na parte 2, substituindo o adaptador provisório de
 * elegibilidade ({@code CaseEligibilityPort}).
 */
@ApplicationModule(displayName = "Negotiation",
        allowedDependencies = {"shared", "organization :: usecases", "identity :: usecases", "debt :: usecases"})
package br.com.gestorcobranca.negotiation;

import org.springframework.modulith.ApplicationModule;
