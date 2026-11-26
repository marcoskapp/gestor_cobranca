/**
 * Kernel compartilhado: tipos de valor, tenancy, segurança base, auditoria, logs,
 * eventos de integração (linguagem publicada), criptografia, armazenamento, jobs e tempo limite de consulta ({@code persistence}: o das portas
 * {@code :: creditor} mora aqui porque o {@code creditorportal} depende dos donos).
 * <p>
 * É um módulo aberto: todos os demais módulos podem depender de qualquer subpacote.
 * Por isso <strong>não</strong> pode depender de nenhum outro módulo.
 */
@ApplicationModule(displayName = "Shared Kernel", type = ApplicationModule.Type.OPEN)
package br.com.gestorcobranca.shared;

import org.springframework.modulith.ApplicationModule;
