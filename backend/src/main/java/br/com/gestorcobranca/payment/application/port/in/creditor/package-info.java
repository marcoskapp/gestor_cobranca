/**
 * Interface do payment para o portal do credor (ADR 0011, decisão 1413): o pagamento recebido diretamente pelo credor
 * e informado pela própria pessoa do credor, sempre revisado pela assessoria. Só o {@code creditorportal} declara
 * {@code payment :: creditor}.
 */
@NamedInterface("creditor")
package br.com.gestorcobranca.payment.application.port.in.creditor;

import org.springframework.modulith.NamedInterface;
