/**
 * Portas que o payment <b>declara</b> e outros módulos <b>implementam</b> (inversão de dependência — ADR-0010). Só
 * módulos que já podem depender do payment pelo grafo ({@code docs/arquitetura/visao-geral.md} §2.1) declaram
 * {@code "payment :: spi"} para implementá-las; o payment nunca depende deles.
 * <p>
 * {@link br.com.gestorcobranca.payment.application.port.spi.IntegrationTokenLabels}: o publicapi informa o nome e o
 * prefixo dos tokens de integração que registraram pagamentos (H07), para a tela mostrar quem informou sem que o
 * payment conheça o publicapi.
 */
@NamedInterface("spi")
package br.com.gestorcobranca.payment.application.port.spi;

import org.springframework.modulith.NamedInterface;
