/**
 * Dívida (M02/F05, RB01, RB03): devedores identificados por documento, contatos com dados
 * pessoais criptografados, contratos de origem, títulos com ciclo de vida, razão do título por
 * componente (principal, juros, multa, encargos, honorários), saldos informados pelo credor e
 * histórico de titularidade.
 * <p>
 * Outros módulos usam somente {@code application.port.in}: casos de uso web
 * ({@code DebtorUseCases}, {@code DebtorContactUseCases}, {@code DebtTitleUseCases},
 * {@code DebtorSheetQueries}) e portas internas sem endpoint ({@code DebtLedgerPort} para
 * pagamento/negociação, {@code DebtIngestionPort} para a importação).
 */
@ApplicationModule(displayName = "Debt", allowedDependencies = {"shared", "organization :: usecases"})
package br.com.gestorcobranca.debt;

import org.springframework.modulith.ApplicationModule;
