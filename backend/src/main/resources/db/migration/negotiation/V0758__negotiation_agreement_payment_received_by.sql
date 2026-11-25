-- H07 (decisão 1466): quem recebeu o dinheiro do pagamento aplicado ao acordo (PaymentConfirmed.receivedBy). Leva o
-- receptor ao razão do título no lançamento do acordo (selo "Pago ao credor") e marca o pagamento na tela do acordo.
-- Registros anteriores ficam sem receptor (valem assessoria; decisão 1521).
alter table negotiation.agreement_payment
    add column received_by varchar(10),
    add constraint ck_agreement_payment_received_by check (received_by is null or received_by in ('AGENCY', 'CREDITOR'));
