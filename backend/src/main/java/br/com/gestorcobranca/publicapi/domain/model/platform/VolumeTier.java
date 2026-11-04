package br.com.gestorcobranca.publicapi.domain.model.platform;

import java.math.BigDecimal;

/**
 * Faixa de volume do preço escalonado das chamadas (padrão "graduado" das assinaturas SaaS): cada chamada excedente é
 * cobrada pelo preço da faixa em que cai, contando as chamadas do ciclo desde a primeira.
 *
 * @param upTo      última chamada do ciclo (contagem acumulada, inclusiva) que esta faixa alcança; {@code null} na
 *                  última faixa (sem limite)
 * @param unitPrice preço por chamada excedente dentro da faixa (até 6 casas)
 */
public record VolumeTier(Long upTo, BigDecimal unitPrice) {
}
