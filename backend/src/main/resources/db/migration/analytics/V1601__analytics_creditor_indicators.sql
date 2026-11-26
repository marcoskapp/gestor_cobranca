-- =============================================================================
-- Indicadores do credor no portal do credor (fatia 7, ADR 0011; docs/tecnico/desenho/portal-credor.md §3f).
--
-- agreement_cohort ganha a foto do acordo na data de referência do refresh:
--   * overdue_installments: parcelas vencidas (vencimento antes da data de referência) e não pagas
--     (situação fora de PAID e CANCELLED);
--   * break_suspended: há suspensão da quebra em vigor (pagamento informado em análise — negotiation V0753).
-- "Acordo em dia" = ativo (FORMALIZED, IN_PAYMENT) sem parcela vencida e não paga, ou com a quebra suspensa
-- (decisão do responsável de 26/11/2026; a regra fica em CreditorIndicatorRules).
--
-- recovery_daily ganha received_by no grão (AGENCY ou CREDITOR, de settlement.receipt): "parte paga direto ao credor"
-- do recuperado. As colunas são recalculadas no próximo refresh (R__analytics_contract.sql); até lá valem os padrões.
-- =============================================================================

alter table analytics.agreement_cohort
    add column overdue_installments integer not null default 0,
    add column break_suspended boolean not null default false,
    add constraint ck_agreement_cohort_overdue check (overdue_installments >= 0);

-- acordos ativos do credor (o portal lê só os do credor da sessão)
create index ix_agreement_cohort_creditor_active on analytics.agreement_cohort (tenant_id, creditor_id)
    where status in ('FORMALIZED', 'IN_PAYMENT');

alter table analytics.recovery_daily
    add column received_by varchar(10) not null default 'AGENCY',
    add constraint ck_recovery_daily_received_by check (received_by in ('AGENCY', 'CREDITOR'));
