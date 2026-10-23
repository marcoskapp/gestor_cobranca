/**
 * Compliance (M05/M09, RB02, RB07, RB09/LGPD): restrições de contato e opt-out, políticas de
 * contato versionadas (janela, feriados, limites e intervalo), registro de tentativas, porteiro
 * de contato, bases legais por credor, solicitações de titulares (LGPD art. 18) e retenção e
 * descarte.
 * <p>
 * Outros módulos usam somente {@code application.port.in}. O contrato central é
 * {@code ContactGate}: communication, campaign, telephony e collection consultam o porteiro antes
 * de qualquer contato e registram a tentativa por {@code ContactGate#authorizeAndRecord}.
 */
@ApplicationModule(displayName = "Compliance",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases"})
package br.com.gestorcobranca.compliance;

import org.springframework.modulith.ApplicationModule;
