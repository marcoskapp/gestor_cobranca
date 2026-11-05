/**
 * Portas que o debt <b>declara</b> e outros módulos <b>implementam</b> (inversão de dependência — ADR-0010).
 * Só módulos que já podem depender do debt pelo grafo ({@code docs/arquitetura/visao-geral.md} §2.1) declaram
 * {@code "debt :: spi"} para implementá-las; o debt nunca depende deles.
 * <p>
 * {@link br.com.gestorcobranca.debt.application.port.spi.TitleAgreementGuard}: o negotiation informa se um título está
 * num acordo em vigor (a correção manual do valor fica impedida até renegociar ou cancelar o acordo).
 */
@NamedInterface("spi")
package br.com.gestorcobranca.debt.application.port.spi;

import org.springframework.modulith.NamedInterface;
