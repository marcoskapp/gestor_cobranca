-- =============================================================================
-- Categoria do template no provedor (docs/tecnico/desenho/comunicacao.md §2):
-- UTILITY, MARKETING ou AUTHENTICATION — só no WhatsApp. O template de autenticação
-- (código de acesso do portal) tem corpo fixo da Meta e um único parâmetro, o código,
-- repetido no botão "copiar código" no envio. Templates de WhatsApp existentes passam a
-- UTILITY (formato de envio de antes: só parâmetros do corpo).
-- =============================================================================
alter table communication.message_template
    add column provider_category varchar(20);

-- RLS forçado: a atualização dos templates existentes roda assessoria por assessoria
do $$
declare
    t record;
begin
    for t in select id from organization.tenant loop
        perform set_config('app.tenant_id', t.id::text, true);
        update communication.message_template set provider_category = 'UTILITY' where channel = 'WHATSAPP';
    end loop;
    perform set_config('app.tenant_id', '', true);
end
$$;

alter table communication.message_template
    add constraint ck_template_provider_category
        check (provider_category in ('UTILITY', 'MARKETING', 'AUTHENTICATION')),
    add constraint ck_template_provider_category_channel
        check ((channel = 'WHATSAPP') = (provider_category is not null));
