-- =============================================================================
-- Debt: recuperação judicial, recuperação extrajudicial e falência do devedor pessoa jurídica
-- (Lei 11.101/2005; cobrança B2B, docs/tecnico/desenho/cobranca-b2b.md §7, B6).
--
-- Registro e encerramento manuais (DEBTOR_MANAGE, com motivo e auditoria). Enquanto houver processo ACTIVE, os casos
-- do devedor ficam inelegíveis no collection (motivo DEBTOR_INSOLVENCY): sem contato nem régua. Interação já
-- registrada não é apagada e acordo em vigor não é cancelado (decisão humana: a dívida pode estar no plano).
-- Só PJ (conferido no domínio pelo tipo de pessoa do devedor). Número do processo no padrão CNJ (20 dígitos, validado
-- no domínio pelo módulo 97). A convolação da recuperação judicial em falência (art. 73) muda o tipo do mesmo processo.
-- Texto livre (nota e motivo do encerramento) é neutralizado na anonimização do devedor.
-- =============================================================================
create table debt.insolvency_proceeding (
    id              uuid          not null primary key,
    tenant_id       uuid          not null references organization.tenant (id),
    version         bigint        not null,
    created_at      timestamptz   not null,
    created_by      uuid,
    updated_at      timestamptz   not null,
    updated_by      uuid,
    debtor_id       uuid          not null references debt.debtor (id),
    kind            varchar(30)   not null,
    case_number     varchar(20)   not null,
    court           varchar(150),
    filed_on        date          not null,
    converted_from  varchar(30),
    converted_on    date,
    status          varchar(10)   not null,
    ended_on        date,
    end_reason      varchar(500),
    note            varchar(500),
    constraint ck_insolvency_kind check (kind in ('JUDICIAL_RECOVERY', 'EXTRAJUDICIAL_RECOVERY', 'BANKRUPTCY')),
    constraint ck_insolvency_status check (status in ('ACTIVE', 'ENDED')),
    constraint ck_insolvency_case_number check (case_number ~ '^[0-9]{20}$'),
    constraint ck_insolvency_ended check ((status = 'ENDED') = (ended_on is not null)),
    constraint ck_insolvency_end_reason check (status = 'ACTIVE' or end_reason is not null),
    constraint ck_insolvency_ended_after_filed check (ended_on is null or ended_on >= filed_on),
    -- convolação: só recuperação judicial vira falência (Lei 11.101/2005, art. 73)
    constraint ck_insolvency_conversion check ((converted_from is null) = (converted_on is null)
                                               and (converted_from is null
                                                    or (converted_from = 'JUDICIAL_RECOVERY' and kind = 'BANKRUPTCY'
                                                        and converted_on >= filed_on)))
);
-- no máximo um processo em curso por devedor
create unique index uk_insolvency_active on debt.insolvency_proceeding (tenant_id, debtor_id) where status = 'ACTIVE';
create index ix_insolvency_debtor on debt.insolvency_proceeding (tenant_id, debtor_id, filed_on desc);
select shared.enable_tenant_rls('debt.insolvency_proceeding');
grant select, insert, update on debt.insolvency_proceeding to app_runtime;
