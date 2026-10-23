# ADR-0002 — Multi-tenancy em schema compartilhado com Row Level Security

**Contexto.** Várias assessorias usam a mesma plataforma com dados sensíveis de devedores. Um vazamento entre assessorias é o pior incidente possível.

**Alternativas.** Banco por tenant (isolamento máximo, custo e operação altos); schema por tenant (migrations N vezes, limite prático de centenas de schemas); schema compartilhado com coluna `tenant_id` (barato, mas depende de o código nunca esquecer o filtro).

**Decisão.** Schema compartilhado com `tenant_id` em toda tabela, mais Row Level Security **forçado** no PostgreSQL. A aplicação conecta com um papel sem `BYPASSRLS` e que não é dono das tabelas; o tenant da transação é definido por `set_config('app.tenant_id', ..., true)` no início de cada transação (`TenantAwareJpaTransactionManager`). Sem tenant, nenhuma linha é visível.

**Consequências.** Um erro de filtro no código não vaza dados: o banco recusa. Jobs e consumidores precisam executar explicitamente no tenant (`TenantExecution`). Consultas entre tenants (só operações de plataforma) usam tabelas sem RLS desenhadas para isso (`organization.tenant`, `shared.processing_job`).
