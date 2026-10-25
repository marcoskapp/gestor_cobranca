-- Permissões padrão já aplicadas a cada perfil de sistema (DefaultRoles). A sincronização de perfis
-- padrão acrescenta a um perfil de sistema somente as permissões da definição que ainda não constam
-- aqui: permissões novas de versões futuras chegam aos perfis existentes, e uma permissão retirada
-- pelo administrador depois de aplicada não volta.
create table identity.role_default_baseline (
    role_id     uuid         not null references identity.role (id) on delete cascade,
    permission  varchar(60)  not null,
    primary key (role_id, permission)
);
alter table identity.role_default_baseline enable row level security;
alter table identity.role_default_baseline force row level security;
create policy tenant_isolation on identity.role_default_baseline
    using (exists (select 1 from identity.role r where r.id = role_id))
    with check (exists (select 1 from identity.role r where r.id = role_id));
grant select, insert, delete on identity.role_default_baseline to app_runtime;

-- Ponto de partida: o que cada perfil de sistema já tem hoje conta como aplicado. O que faltar em
-- relação à definição atual (ex.: permissões criadas depois do provisionamento da assessoria) será
-- acrescentado na primeira sincronização. As tabelas têm RLS: percorre as assessorias definindo o
-- tenant da transação, como a aplicação faria.
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        insert into identity.role_default_baseline (role_id, permission)
        select rp.role_id, rp.permission
          from identity.role_permission rp
          join identity.role r on r.id = rp.role_id
         where r.system_role
        on conflict do nothing;
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;
