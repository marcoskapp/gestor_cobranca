package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.PaymentDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataRecipientType;
import br.com.gestorcobranca.shared.privacy.DataSharingFact;
import br.com.gestorcobranca.shared.privacy.DataSharingLink;
import br.com.gestorcobranca.shared.privacy.DataSharingPurpose;
import br.com.gestorcobranca.shared.privacy.DataSharingQuery;
import br.com.gestorcobranca.shared.privacy.DataSharingRecipient;
import br.com.gestorcobranca.shared.privacy.DataSharingSource;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Cobranças Pix do devedor, por conta Pix: o PSP da conta recebe nome, CPF/CNPJ e valor para registrar a cobrança. Cada
 * conta é um destinatário (o PSP aparece no nome da conta). O link abre as cobranças filtradas pelo devedor.
 */
@Component
class PaymentDataSharing implements DataSharingSource {

    private final PaymentDataSharingQueries queries;

    PaymentDataSharing(PaymentDataSharingQueries queries) {
        this.queries = queries;
    }

    @Override
    public String id() {
        return "payment";
    }

    @Override
    public String label() {
        return "Pagamentos";
    }

    @Override
    public List<DataSharingFact> sharedWith(DataSharingQuery query) {
        DataSharingLink charges = DataSharingLink.to("/pagamentos/instrucoes", "Ver as cobranças do devedor",
                Permission.PAYMENT_VIEW).withParam("devedor", query.debtorId().toString());
        return queries.pixChargesByAccount(query.debtorId(), query.creditorScope()).stream()
                .map(u -> new DataSharingFact(DataRecipientType.PROVIDER, key(u.recipientId()), u.recipientId(), u.name(),
                        DataSharingPurpose.PAYMENT_COLLECTION, u.firstAt(), u.lastAt(), u.count(),
                        DataSharingFact.counted(u.count(), "cobrança Pix", "cobranças Pix"), charges))
                .toList();
    }

    @Override
    public List<DataSharingRecipient> recipientsInUse() {
        return queries.activeAccounts().stream()
                .map(a -> new DataSharingRecipient(key(a.recipientId()), a.name(), DataSharingPurpose.PAYMENT_COLLECTION))
                .toList();
    }

    static String key(UUID accountId) {
        return "payment.pix_account:" + accountId;
    }
}
