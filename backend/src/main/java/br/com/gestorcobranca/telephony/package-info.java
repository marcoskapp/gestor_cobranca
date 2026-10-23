/**
 * Telefonia e discador (F20): contas do provedor (porta {@code TelephonyProvider}; provedor real
 * pendente — issue #12 — e simulador para local/teste), chamadas vinculadas ao caso com número
 * criptografado, clique para ligar da estação de cobrança, campanhas de discagem progressivas e
 * preditivas com abandono máximo, retentativas e janela, distribuição da chamada atendida ao
 * operador livre, presença do operador (ACD), tabulação que registra a interação no caso,
 * gravações com acesso registrado e webhooks assinados.
 * <p>
 * Toda discagem passa por {@code ContactGate#authorizeAndRecord} do compliance. Outros módulos
 * usam somente {@code application.port.in}; o encerramento publica
 * {@code shared.events.telephony.CallCompleted} (consumido pelo incentive). Ver
 * {@code docs/tecnico/desenho/telefonia.md}.
 */
@ApplicationModule(displayName = "Telephony",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases", "compliance :: usecases",
                "collection :: usecases"})
package br.com.gestorcobranca.telephony;

import org.springframework.modulith.ApplicationModule;
