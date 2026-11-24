/**
 * Interface do collection para o portal do credor (ADR 0011, decisão 1413): as contestações em que a assessoria pediu a
 * posição do credor e a resposta da própria pessoa do credor, só para o ator {@code CREDITOR}. Separada de
 * {@code usecases}: só o módulo {@code creditorportal} declara {@code collection :: creditor}, e ele não enxerga as
 * portas da assessoria.
 */
@NamedInterface("creditor")
package br.com.gestorcobranca.collection.application.port.in.creditor;

import org.springframework.modulith.NamedInterface;
