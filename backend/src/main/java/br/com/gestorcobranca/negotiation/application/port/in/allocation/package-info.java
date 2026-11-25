/**
 * Interface do negotiation para a alocação de pagamentos (decisão 1435): só o {@code payment} declara
 * {@code negotiation :: allocation} ({@code ArchitectureTest}). Projeção estreita do acordo — o necessário para validar
 * o destino e alocar —, nunca exibida.
 * <p>
 * Permissão: {@code AGREEMENT_VIEW} para todo ator, salvo a pessoa do credor (portal) e o token de
 * integração com {@code PAYMENT_REGISTER_EXTERNAL} ({@code payments:write}, decisão 1518), que só informam o pagamento
 * recebido pelo credor e precisam saber o que o acordo cobre, sem que isso vire consulta de acordos. O escopo por credor é sempre conferido. Um
 * consumidor novo desta interface herda essa liberação: só o payment pode declará-la.
 */
@NamedInterface("allocation")
package br.com.gestorcobranca.negotiation.application.port.in.allocation;

import org.springframework.modulith.NamedInterface;
