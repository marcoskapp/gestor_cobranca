/**
 * Portas que o negotiation <b>declara</b> e outros módulos <b>implementam</b> (inversão de dependência —
 * ADR-0010). Só módulos que já podem depender do negotiation pelo grafo
 * ({@code docs/arquitetura/visao-geral.md} §2.1) declaram {@code "negotiation :: spi"} para implementá-las;
 * o negotiation nunca depende deles.
 * <p>
 * {@link br.com.gestorcobranca.negotiation.application.port.spi.PaymentReviewHolds}: o payment informa,
 * da fonte, os pagamentos externos em revisão e as alocações em trânsito de um acordo, para que a quebra
 * fique suspensa enquanto a revisão não termina.
 */
@NamedInterface("spi")
package br.com.gestorcobranca.negotiation.application.port.spi;

import org.springframework.modulith.NamedInterface;
