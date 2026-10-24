/**
 * Painéis e relatórios gerenciais (M08; F11 painel operacional, F18 recuperação e produtividade) —
 * docs/tecnico/desenho/analytics.md.
 * <p>
 * Não chama casos de uso de outros módulos: lê o banco por views próprias do schema
 * {@code analytics} ({@code security_invoker}, RLS da assessoria preservado), cujo contrato de
 * colunas fica em {@code db/migration/analytics/R__analytics_contract.sql} e é conferido por teste.
 * Snapshots por assessoria são reconstruídos por job (ShedLock) e carimbados com "dados até".
 */
@ApplicationModule(displayName = "Analytics", allowedDependencies = {"shared", "organization :: usecases"})
package br.com.gestorcobranca.analytics;

import org.springframework.modulith.ApplicationModule;
