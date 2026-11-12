-- =============================================================================
-- Organization: regime da carteira (cobrança B2B, docs/tecnico/desenho/cobranca-b2b.md §6, B5).
--
-- CONSUMER (padrão): relação de consumo (CDC) — vale o alerta de multa acima de 2% na revisão da carga e o
-- marcador de superendividamento (Lei 14.181/2021). BUSINESS: relação entre empresas (Código Civil e contrato).
-- Proteção nunca é retirada por inferência: as carteiras existentes e as criadas sem regime ficam em CONSUMER.
-- O regime não muda contrato existente; a política de negociação continua sendo a fonte das condições.
-- =============================================================================
alter table organization.portfolio
    add column regime varchar(20) not null default 'CONSUMER',
    add constraint ck_portfolio_regime check (regime in ('CONSUMER', 'BUSINESS'));
