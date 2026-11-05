/**
 * Portas que o collection <b>declara</b> e outros módulos <b>implementam</b> (inversão de dependência — ADR-0010).
 * Só módulos que já podem depender do collection pelo grafo ({@code docs/arquitetura/visao-geral.md} §2.1) declaram
 * {@code "collection :: spi"} para implementá-las; o collection nunca depende deles.
 * <p>
 * {@link br.com.gestorcobranca.collection.application.port.spi.DisputeDebtorMessenger}: o communication envia a
 * resposta da contestação ao devedor como mensagem transacional, pelo porteiro de contato, e informa a situação.
 */
@NamedInterface("spi")
package br.com.gestorcobranca.collection.application.port.spi;

import org.springframework.modulith.NamedInterface;
