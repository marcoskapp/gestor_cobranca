/**
 * Interface do settlement para o portal do credor (ADR 0011, decisão 1413): leituras e respostas da própria pessoa do
 * credor, só para o ator {@code CREDITOR}. Separada de {@code usecases}: só o módulo {@code creditorportal} declara
 * {@code settlement :: creditor}, e ele não enxerga as portas da assessoria.
 */
@NamedInterface("creditor")
package br.com.gestorcobranca.settlement.application.port.in.creditor;

import org.springframework.modulith.NamedInterface;
