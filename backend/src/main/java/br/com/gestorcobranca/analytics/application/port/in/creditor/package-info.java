/**
 * Interface do analytics para o portal do credor (ADR 0011, decisão 1413; fatia 7): os indicadores do próprio credor,
 * só para o ator {@code CREDITOR}. Separada de {@code usecases}: só o módulo {@code creditorportal} declara
 * {@code analytics :: creditor}, e ele não enxerga as consultas da assessoria (que trazem comissão e dimensões por
 * pessoa).
 */
@NamedInterface("creditor")
package br.com.gestorcobranca.analytics.application.port.in.creditor;

import org.springframework.modulith.NamedInterface;
