/**
 * Interface do módulo identity para o portal do credor (ADR 0011): autenticação da pessoa do credor e a nova
 * verificação do MFA nas ações. Separada de {@code usecases}: só o módulo {@code creditorportal} (e o sandbox)
 * declaram {@code identity :: creditor}.
 */
@NamedInterface("creditor")
package br.com.gestorcobranca.identity.application.port.in.creditor;

import org.springframework.modulith.NamedInterface;
