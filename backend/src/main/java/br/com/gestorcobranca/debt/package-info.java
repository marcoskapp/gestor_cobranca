/**
 * Dívida (M02/F05, RB01, RB03): devedores identificados por documento, contatos com dados
 * pessoais criptografados, contratos de origem com natureza do título, títulos com ciclo de
 * vida, razão do título por componente (principal, juros, multa, encargos, honorários), saldos
 * informados pelo credor, histórico de titularidade, garantias com avaliações, partes da
 * obrigação (coobrigados) e prescrição por pretensão com regras configuráveis por assessoria
 * ({@code docs/tecnico/desenho/juridico.md}, Parte A).
 * <p>
 * Outros módulos usam somente {@code application.port.in}: casos de uso web
 * ({@code DebtorUseCases}, {@code DebtorContactUseCases}, {@code DebtTitleUseCases},
 * {@code DebtorSheetQueries}, {@code OriginContractUseCases}, {@code CollateralUseCases},
 * {@code ObligationPartyUseCases}, {@code PrescriptionRuleUseCases},
 * {@code TitlePrescriptionUseCases}) e portas internas sem endpoint ({@code DebtLedgerPort} para
 * pagamento/negociação, {@code DebtIngestionPort} para a importação,
 * {@code PrescriptionInterruptionPort} para o módulo legal).
 */
@ApplicationModule(displayName = "Debt", allowedDependencies = {"shared", "organization :: usecases"})
package br.com.gestorcobranca.debt;

import org.springframework.modulith.ApplicationModule;
