-- H07 parte 2b: o que a carga do credor fez com o título, gravado de forma estruturada para a lista "Baixas informadas
-- pelo credor" (aba "Pela carga do credor") e para o destaque no título.
--
-- 1. Saldo zerado pela carga: a redução do principal informada pelo credor levou o título a SETTLED (resposta 2 do
--    responsável, 25/11/2026). Lote e instante gravados só pelo servidor, no caminho da carga, na mesma transação do
--    ajuste no razão; limpos quando o título sai de SETTLED (reabre).
-- 2. Suspensão por "credor informou pagamento/cancelamento": motivo codificado e o lote em processamento. Sem
--    tratamento dos títulos suspensos antes desta versão (só com o texto do motivo): o sistema não está em produção e o
--    sandbox é recriado (decisão 1579); o texto nunca é convertido em id.
--
-- Expand/contract: só colunas novas, anuláveis, e checks que a versão anterior da aplicação (que não conhece as
-- colunas) não viola. A coerência com o status (lote só em SETTLED, motivo só em SUSPENDED) fica para uma migration de
-- contract depois que todas as instâncias rodarem esta versão — antes disso, uma instância antiga que reabre o título
-- não limparia as colunas e teria a transação recusada. A leitura sempre filtra pelo status.
alter table debt.debt_title
    add column settled_by_import_batch_id uuid,
    add column settled_by_import_at timestamptz,
    add column suspension_reason_code varchar(30),
    add column suspension_import_batch_id uuid,
    add column suspended_at timestamptz,
    add constraint ck_debt_title_settled_by_import
        check ((settled_by_import_batch_id is null) = (settled_by_import_at is null)),
    add constraint ck_debt_title_suspension_reason_code
        check (suspension_reason_code is null
            or suspension_reason_code in ('CREDITOR_REPORTED_PAID', 'CREDITOR_REPORTED_CANCELLED')),
    add constraint ck_debt_title_suspension_import_batch
        check ((suspension_reason_code is null and suspension_import_batch_id is null and suspended_at is null)
            or (suspension_reason_code is not null and suspension_import_batch_id is not null
                and suspended_at is not null));

-- aba "Pela carga do credor": poucas linhas, sempre com o escopo por credor e o período
create index ix_debt_title_settled_by_import on debt.debt_title (tenant_id, creditor_id, settled_by_import_at desc, id)
    where settled_by_import_batch_id is not null;
create index ix_debt_title_creditor_report on debt.debt_title (tenant_id, creditor_id, suspended_at desc, id)
    where suspension_reason_code is not null;
