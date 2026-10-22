package br.com.gestorcobranca.shared.logging.business;

/**
 * Registra eventos de negócio. Cada evento:
 * <ol>
 *   <li>é gravado na tabela {@code shared.business_event} <strong>na mesma transação</strong>
 *       da operação (se a operação for desfeita, o evento também é);</li>
 *   <li>é emitido no log {@code BUSINESS} (CloudWatch {@code /business}) somente após o commit.</li>
 * </ol>
 * Tenant, ator, sessão e requestId são preenchidos automaticamente.
 */
public interface BusinessEventLogger {

    void log(BusinessEvent event);
}
