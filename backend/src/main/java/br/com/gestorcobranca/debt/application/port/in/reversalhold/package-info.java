/**
 * Interface do debt para criar a retenção da cobrança na transação do estorno de pagamento recebido pelo credor (H07
 * parte 3, AQ-02): só o {@code payment} declara {@code debt :: reversal-hold} e só ele a chama
 * ({@code ArchitectureTest}). Sem permissão própria: quem chama já autorizou o estorno ({@code PAYMENT_REVERSE} ou o
 * provedor de pagamento); exige a transação do chamador.
 */
@NamedInterface("reversal-hold")
package br.com.gestorcobranca.debt.application.port.in.reversalhold;

import org.springframework.modulith.NamedInterface;
