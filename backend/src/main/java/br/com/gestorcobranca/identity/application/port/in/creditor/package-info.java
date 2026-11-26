/**
 * Interface do módulo identity para o portal do credor (ADR 0011): autenticação da pessoa do credor e a nova
 * verificação do MFA nas ações. Separada de {@code usecases}: só o módulo {@code creditorportal} declara
 * {@code identity :: creditor} (a carga do sandbox age como credor pela API HTTP do portal, decisão 1566).
 */
@NamedInterface("creditor")
package br.com.gestorcobranca.identity.application.port.in.creditor;

import org.springframework.modulith.NamedInterface;
