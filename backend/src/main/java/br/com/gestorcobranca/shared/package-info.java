/**
 * Kernel compartilhado: tipos de valor, tenancy, segurança base, auditoria, logs,
 * eventos de integração (linguagem publicada), criptografia, armazenamento e jobs.
 * <p>
 * É um módulo aberto: todos os demais módulos podem depender de qualquer subpacote.
 * Por isso <strong>não</strong> pode depender de nenhum outro módulo.
 */
@ApplicationModule(displayName = "Shared Kernel", type = ApplicationModule.Type.OPEN)
package br.com.gestorcobranca.shared;

import org.springframework.modulith.ApplicationModule;
