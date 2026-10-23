/**
 * Negociação (M06, F08, F09, F16, RB03–RB05) — ver docs/tecnico/desenho/negociacao-e-alcadas.md.
 * <p>
 * Parte 1 (este estado): políticas de cobrança versionadas por credor com sobreposições por
 * carteira e faixa de atraso, tabela manual de índices de correção, motor de cálculo puro com
 * memória de cálculo, envelope de concessões e escada de níveis, perfis de alçada (humanos e
 * assistente) com atribuição por usuário/credor, simulação persistida e {@code NegotiationGuard}
 * — a validação única pela qual passam humanos e o assistente.
 * <p>
 * <p>
 * Parte 2: propostas a partir de simulações com aprovação roteada por alçada (F16) e transferência
 * do assistente para humano acima da alçada; acordos com cronograma, evidência de aceite e termo
 * em PDF; baixa de parcelas por {@code PaymentConfirmed}/{@code PaymentReversed} com lançamentos no
 * razão do título; quebra pela regra da política; renegociação; atribuição do acordo.
 * <p>
 * Dependências: {@code organization} (credores e carteiras), {@code identity} (usuários para a
 * atribuição de alçada), {@code debt} (títulos, saldos por componente e razão) e {@code collection}
 * (caso de cobrança e contestações — elegibilidade). Reações a outros módulos por eventos de
 * integração ({@code shared.events.payment}, {@code .debt}, {@code .identity}).
 */
@ApplicationModule(displayName = "Negotiation",
        allowedDependencies = {"shared", "organization :: usecases", "identity :: usecases", "debt :: usecases",
                "collection :: usecases"})
package br.com.gestorcobranca.negotiation;

import org.springframework.modulith.ApplicationModule;
