-- =============================================================================
-- Identificador com CPF/CNPJ trocado na anonimização do titular (SEG-20261125-K02, R-12, decisão 1476).
--
-- external_id: com o titular anonimizado e o documento embutido, passa a 'anon:' + HMAC (64 hex) do credor e do
-- identificador (shared.crypto.ExternalIdDigest). A carga e a detecção nos arquivos comparam pelas duas formas.
-- external_id_display: forma mascarada para exibição (Document.maskIfDocument), gravada na troca. Até 1.440
-- caracteres: o NFKC da máscara expande caracteres compatíveis (SEG-20261126-M01, decisão 1485).
--
-- Gatilho: a única troca aceita é de ida — do valor informado para 'anon:<64 hex>', com o devedor já anonimizado; a
-- forma de exibição só é gravada junto e não muda depois. O prefixo 'anon:' é reservado na entrada (domínio e check).
-- =============================================================================

-- nenhum identificador informado pode já começar com o prefixo reservado; RLS forçada: percorre as assessorias com o
-- tenant da transação (decisão 849)
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        if exists (select 1 from debt.debt_title where external_id ilike 'anon:%')
            or exists (select 1 from debt.origin_contract where external_id ilike 'anon:%') then
            raise exception 'Identificador gravado com o prefixo reservado anon: — revise antes de aplicar a V0316';
        end if;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;

alter table debt.debt_title
    add column external_id_display text,
    add constraint ck_debt_title_external_id_display check (
        (external_id ~ '^anon:[0-9a-f]{64}$') = (external_id_display is not null)
        and (external_id_display is null or length(external_id_display) between 1 and 1440)),
    add constraint ck_debt_title_external_id_reserved check (
        external_id !~* '^anon:' or external_id ~ '^anon:[0-9a-f]{64}$');

alter table debt.origin_contract
    add column external_id_display text,
    add constraint ck_origin_contract_external_id_display check (
        (external_id ~ '^anon:[0-9a-f]{64}$') = (external_id_display is not null)
        and (external_id_display is null or length(external_id_display) between 1 and 1440)),
    add constraint ck_origin_contract_external_id_reserved check (
        external_id !~* '^anon:' or external_id ~ '^anon:[0-9a-f]{64}$');

create function debt.external_id_anonymize_only() returns trigger
    language plpgsql as
$$
begin
    -- inclusão: nunca já trocada (a troca é só de ida, sobre registro existente)
    if tg_op = 'INSERT' then
        if new.external_id ~* '^anon:' or new.external_id_display is not null then
            raise exception 'Identificador externo anonimizado só por troca' using errcode = 'P0001';
        end if;
        return new;
    end if;
    if new.external_id is distinct from old.external_id then
        if old.external_id ~ '^anon:' or new.external_id !~ '^anon:[0-9a-f]{64}$'
            or new.external_id_display is null then
            raise exception 'Identificador externo só muda para a forma anonimizada' using errcode = 'P0001';
        end if;
        if not exists (select 1 from debt.debtor d where d.id = new.debtor_id and d.status = 'ANONYMIZED') then
            raise exception 'Identificador externo só é anonimizado com o devedor anonimizado' using errcode = 'P0001';
        end if;
    elsif new.external_id_display is distinct from old.external_id_display then
        raise exception 'Forma de exibição do identificador é gravada só na troca' using errcode = 'P0001';
    end if;
    return new;
end;
$$;

create trigger trg_debt_title_external_id before insert or update of external_id, external_id_display
    on debt.debt_title for each row execute function debt.external_id_anonymize_only();
create trigger trg_origin_contract_external_id before insert or update of external_id, external_id_display
    on debt.origin_contract for each row execute function debt.external_id_anonymize_only();

-- titulares anonimizados antes desta versão: o job troca os identificadores (o HMAC precisa da chave da aplicação),
-- uma vez por assessoria; idempotente (só o que ainda não foi trocado)
insert into shared.processing_job (id, tenant_id, type, payload, status, priority, run_at, attempts, dedup_key, created_at)
select gen_random_uuid(), t.id, 'debt.external-id-anonymization', '{}'::jsonb, 'PENDING', 0, now(), 0,
       'debt.external-id-anonymization:v1', now()
  from organization.tenant t;
