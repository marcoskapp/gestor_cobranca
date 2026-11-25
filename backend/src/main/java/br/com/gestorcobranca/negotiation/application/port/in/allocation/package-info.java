/**
 * Interface do negotiation para a alocação de pagamentos (decisão 1435): só o {@code payment} declara
 * {@code negotiation :: allocation} ({@code ArchitectureTest}). Projeção estreita do acordo — o necessário para validar
 * o destino e alocar —, nunca exibida.
 * <p>
 * Permissão: {@code AGREEMENT_VIEW} ou {@code PAYMENT_REGISTER_EXTERNAL} (decisão 1518) — quem informa um pagamento
 * precisa saber o que o acordo cobre (inclusive o token de integração só com {@code payments:write}), sem que isso
 * vire consulta de acordos; a pessoa do credor (portal) é dispensada. O escopo por credor é sempre conferido. Um
 * consumidor novo desta interface herda essa liberação: só o payment pode declará-la.
 */
@NamedInterface("allocation")
package br.com.gestorcobranca.negotiation.application.port.in.allocation;

import org.springframework.modulith.NamedInterface;
