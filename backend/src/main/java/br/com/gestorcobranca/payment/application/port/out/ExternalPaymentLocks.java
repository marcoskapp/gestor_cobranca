package br.com.gestorcobranca.payment.application.port.out;

import java.util.UUID;

/**
 * Serializa o registro de pagamentos externos que entram na mesma soma acumulada (mesmo usuário que
 * informa, mesmo devedor no credor e, na integração do credor, o mesmo credor): dois registros simultâneos não
 * podem, cada um abaixo do limite, somar acima dele sem revisão. Locks transacionais, liberados no
 * commit/rollback; sempre adquiridos na mesma ordem (credor da integração, quem informa, depois o devedor), sem ciclo.
 */
public interface ExternalPaymentLocks {

    void lockExternalRegistration(UUID tenantId, UUID informedBy, UUID creditorId, UUID debtorId);

    /** Integração do credor (H07): o teto acumulado por credor, antes das travas de quem informa e do devedor. */
    void lockCreditorIntegration(UUID tenantId, UUID creditorId);

    /** Informes do portal do credor: a contagem do limite em análise por credor (RS-36) sob a mesma trava. */
    void lockCreditorPortalReports(UUID tenantId, UUID creditorId);
}
