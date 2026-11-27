/**
 * Portas que o compliance <b>declara</b> e outros módulos <b>implementam</b> (inversão de dependência — ADR-0010).
 * Só módulos que já podem depender do compliance pelo grafo ({@code docs/arquitetura/visao-geral.md} §2.1) declaram
 * {@code "compliance :: spi"} para implementá-las; o compliance nunca depende deles.
 * <p>
 * {@link br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults}: o módulo dono dos dados (portal,
 * scoring) informa o prazo padrão do sistema das categorias de retenção que ele descarta, exibido na tela de
 * Retenção e usado enquanto a assessoria não configura o próprio.
 * <p>
 * {@link br.com.gestorcobranca.compliance.application.port.spi.NewFileRequests}: o ingestion informa, para o aviso da
 * eliminação ao credor, os lotes bloqueados com o titular e o registro do pedido de novo arquivo (evidência, decisão
 * 1497).
 */
@NamedInterface("spi")
package br.com.gestorcobranca.compliance.application.port.spi;

import org.springframework.modulith.NamedInterface;
