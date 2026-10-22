-- Papéis para o contêiner de testes de integração (equivalente a scripts/db/bootstrap-roles.sql)
create role app_owner login password 'app_owner_it';
create role app_runtime nologin nobypassrls;
create role app_runtime_login login password 'app_runtime_it' nobypassrls in role app_runtime;
alter database cobranca owner to app_owner;
