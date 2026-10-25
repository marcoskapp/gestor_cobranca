-- Revisão de segurança A9: troca de URL do PSP, do token OAuth2 ou da autenticação do webhook deixa a
-- conta Pix inativa até a reativação por outra pessoa (PixAccount#endpointsChanged / #activateBy).
alter table payment.pix_account
    add column endpoints_changed_by uuid,
    add column endpoints_changed_at timestamptz;
