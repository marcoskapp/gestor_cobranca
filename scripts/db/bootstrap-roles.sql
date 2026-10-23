-- =============================================================================
-- Bootstrap de papéis do PostgreSQL (executar UMA vez por ambiente, como superusuário
-- do RDS, antes da primeira migration).
--
--   app_owner           dono dos schemas; usado APENAS pelo Flyway (migrations)
--   app_runtime         papel sem login com os privilégios de runtime (concedidos pelas migrations)
--   app_runtime_login   usuário com que a aplicação conecta; herda app_runtime
--
-- Uso:
--   psql "host=<rds> dbname=cobranca user=<master>" \
--        -v owner_password='...' -v runtime_password='...' -f bootstrap-roles.sql
-- As senhas devem vir do cofre de segredos do ambiente; nunca versionar valores reais.
-- Na AWS, use scripts/deploy/db-admin.sh bootstrap-roles (executa este arquivo numa instância do ASG
-- via SSM Run Command, sem senhas em argumentos de processo; ver infra/terraform/README.md).
-- =============================================================================

\set ON_ERROR_STOP on

select format('create role app_owner login password %L', :'owner_password')
where not exists (select 1 from pg_roles where rolname = 'app_owner') \gexec

select 'create role app_runtime nologin nobypassrls'
where not exists (select 1 from pg_roles where rolname = 'app_runtime') \gexec

select format('create role app_runtime_login login password %L nobypassrls in role app_runtime', :'runtime_password')
where not exists (select 1 from pg_roles where rolname = 'app_runtime_login') \gexec

-- o banco pertence ao dono das migrations.
-- No RDS o usuário mestre não é superusuário: no PostgreSQL 16 ele só pode transferir a
-- posse se puder assumir (SET ROLE) app_owner. A concessão é temporária e revogada logo
-- depois; localmente (superusuário) nada muda.
select (not r.rolsuper and not pg_has_role(current_user, 'app_owner', 'SET')) as needs_set_grant
from pg_roles r where r.rolname = current_user \gset
\if :needs_set_grant
select format('grant app_owner to %I with inherit false, set true', current_user) \gexec
\endif
select format('alter database %I owner to app_owner', current_database()) \gexec
\if :needs_set_grant
select format('revoke app_owner from %I', current_user) \gexec
\endif

-- conexões de runtime limitadas (proteção contra esgotamento por vazamento de conexões)
alter role app_runtime_login connection limit 200;
-- timeout de instruções e de transações ociosas para o runtime
alter role app_runtime_login set statement_timeout = '60s';
alter role app_runtime_login set idle_in_transaction_session_timeout = '120s';
