-- Portal do credor (ADR 0011, fatia 4): origem da resposta do credor ao demonstrativo e o termo do aceite/contestação
-- feito pela própria pessoa do credor (prova: quem, quando, de onde, com nova verificação do MFA e o SHA-256 de cada
-- arquivo que ela viu). O termo é só de inclusão (nunca alterado nem apagado).

alter table settlement.statement add column creditor_response_origin varchar(20);
-- respostas já registradas vieram da assessoria; settlement.statement tem RLS forçada: preenchimento por assessoria
-- (mesmo padrão da decisão 849)
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update settlement.statement set creditor_response_origin = 'AGENCY'
        where creditor_response_at is not null and creditor_response_origin is null;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
alter table settlement.statement add constraint ck_statement_creditor_response_origin
    check (creditor_response_origin is null or creditor_response_origin in ('AGENCY', 'CREDITOR_PORTAL'));
alter table settlement.statement add constraint ck_statement_creditor_response_origin_set
    check ((creditor_response_at is null) = (creditor_response_origin is null));

create table settlement.statement_creditor_term (
    id                uuid           not null primary key,
    tenant_id         uuid           not null references organization.tenant (id),
    version           bigint         not null,
    created_at        timestamptz    not null,
    created_by        uuid,
    updated_at        timestamptz    not null,
    updated_by        uuid,
    statement_id      uuid           not null references settlement.statement (id),
    decision          varchar(20)    not null,
    creditor_user_id  uuid           not null,
    session_id        uuid           not null,
    recorded_at       timestamptz    not null,
    client_ip         varchar(64),
    mfa_verified_at   timestamptz,
    note              varchar(2000),
    files             jsonb          not null,
    term_sha256       varchar(64)    not null,
    constraint ck_statement_creditor_term_decision check (decision in ('ACKNOWLEDGED', 'CONTESTED')),
    constraint ck_statement_creditor_term_mfa check (decision <> 'ACKNOWLEDGED' or mfa_verified_at is not null)
);
create index ix_statement_creditor_term_statement on settlement.statement_creditor_term (tenant_id, statement_id, recorded_at);
select shared.enable_tenant_rls('settlement.statement_creditor_term');
create trigger trg_statement_creditor_term_append_only before update or delete on settlement.statement_creditor_term
    for each row execute function shared.forbid_mutation();
-- os privilégios padrão do schema dão update/delete ao app_runtime: o termo só recebe inclusão (duas camadas)
revoke update, delete on settlement.statement_creditor_term from app_runtime;
