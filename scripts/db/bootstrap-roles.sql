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
-- =============================================================================

\set ON_ERROR_STOP on

select format('create role app_owner login password %L', :'owner_password')
where not exists (select 1 from pg_roles where rolname = 'app_owner') \gexec

select 'create role app_runtime nologin nobypassrls'
where not exists (select 1 from pg_roles where rolname = 'app_runtime') \gexec

select format('create role app_runtime_login login password %L nobypassrls in role app_runtime', :'runtime_password')
where not exists (select 1 from pg_roles where rolname = 'app_runtime_login') \gexec

-- o banco pertence ao dono das migrations
select format('alter database %I owner to app_owner', current_database()) \gexec

-- conexões de runtime limitadas (proteção contra esgotamento por vazamento de conexões)
alter role app_runtime_login connection limit 200;
-- timeout de instruções e de transações ociosas para o runtime
alter role app_runtime_login set statement_timeout = '60s';
alter role app_runtime_login set idle_in_transaction_session_timeout = '120s';
