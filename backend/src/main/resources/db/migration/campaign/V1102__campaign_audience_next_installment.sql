-- =============================================================================
-- Lembrete de parcela de acordo (docs/tecnico/desenho/campanhas.md §2 e §3.3): a
-- projeção de audiência guarda o vencimento da próxima parcela em aberto do acordo em
-- vigor do caso, lido do negotiation (AgreementUseCases) ao atualizar o caso e ao consumir
-- AgreementStatusChanged/InstallmentStatusChanged. Base do filtro de segmento "parcela
-- vence em N dias". Nulo = sem acordo em vigor com parcela em aberto. A reconciliação
-- diária (AudienceRebuildJob) preenche os casos que já estão em acordo.
-- =============================================================================
alter table campaign.audience_case
    add column next_installment_due_date date;

create index ix_audience_next_installment on campaign.audience_case (tenant_id, creditor_id, next_installment_due_date)
    where next_installment_due_date is not null;
