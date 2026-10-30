package br.com.gestorcobranca.settlement.application.port.out;

import java.util.UUID;

/**
 * Marca barata das alterações dos dados do settlement de um credor (recebimentos e despesas em aberto, lançamentos,
 * demonstrativos, ajustes, repasses e acordos). Muda sempre que algo que entra na posição muda — permite servir a
 * posição do cache curto sem risco de mostrar dado próprio desatualizado, inclusive com várias instâncias.
 */
public interface SettlementPositionFingerprints {

    String of(UUID creditorId);
}
