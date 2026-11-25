-- LGPD (SEG-20261124-D01, decisão 1457): o contato do titular anonimizado é neutralizado no lugar (outros módulos o
-- referenciam, alguns por tabela imutável). O estado fica explícito e o banco garante que ele não volta a ser usado:
-- inválido, sem WhatsApp e sem nome nem papel da pessoa.
alter table debt.debtor_contact
    add column anonymized_at timestamptz,
    add constraint ck_debtor_contact_anonymized
        check (anonymized_at is null
            or (status = 'INVALID' and not whatsapp and not decision_maker
                and person_name_encrypted is null and person_role is null));
