package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.CompanyRepresentative;
import br.com.gestorcobranca.debt.domain.model.ContactValue;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

/** Blind indexes dos dados pessoais do módulo, sempre no tenant corrente. */
@Component
class PersonalDataIndex {

    private final BlindIndex blindIndex;
    private final CurrentActor currentActor;

    PersonalDataIndex(BlindIndex blindIndex, CurrentActor currentActor) {
        this.blindIndex = blindIndex;
        this.currentActor = currentActor;
    }

    String document(Document document) {
        return blindIndex.of(currentActor.tenantId(), Debtor.DOCUMENT_HASH_PURPOSE, document.number());
    }

    /** Raiz do CNPJ (matriz e filiais); nulo para CPF. */
    String documentRoot(Document document) {
        return document.cnpjRoot()
                .map(root -> blindIndex.of(currentActor.tenantId(), Debtor.DOCUMENT_ROOT_HASH_PURPOSE, root))
                .orElse(null);
    }

    /** CPF do representante da empresa (finalidade própria, diferente da do devedor). */
    String representative(Document cpf) {
        return blindIndex.of(currentActor.tenantId(), CompanyRepresentative.DOCUMENT_HASH_PURPOSE, cpf.number());
    }

    String contact(ContactValue value) {
        return blindIndex.of(currentActor.tenantId(), value.hashPurpose(), value.hashKey());
    }
}
