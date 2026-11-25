/**
 * Interface do negotiation para a alocação de pagamentos (decisão 1435): só o {@code payment} declara
 * {@code negotiation :: allocation} ({@code ArchitectureTest}). Projeção estreita do acordo — o necessário para validar
 * o destino e alocar —, nunca exibida.
 */
@NamedInterface("allocation")
package br.com.gestorcobranca.negotiation.application.port.in.allocation;

import org.springframework.modulith.NamedInterface;
