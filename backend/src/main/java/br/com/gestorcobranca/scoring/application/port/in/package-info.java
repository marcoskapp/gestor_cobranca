/**
 * Interface pública do módulo scoring: casos de uso e seus records de entrada/saída. Nenhum outro
 * módulo depende do scoring (o grafo não permite); os consumidores recebem o score pelos eventos
 * {@code CaseScoreChanged} e {@code ExpectedRecoveryMeasured}. O pacote é a fronteira da camada web.
 */
@NamedInterface("usecases")
package br.com.gestorcobranca.scoring.application.port.in;

import org.springframework.modulith.NamedInterface;
