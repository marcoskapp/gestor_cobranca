package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Raiz do CNPJ (B1) dos cadastros de empresa anteriores a ela: o blind index usa a chave da aplicação e não pode ser
 * calculado pela migration. Em lotes, no tenant corrente; a carga também preenche ao reencontrar a empresa. Sem
 * auditoria por devedor: é um índice técnico derivado do documento já cadastrado, sem mudança de dado pessoal.
 */
@Component
class DocumentRootBackfill {

    static final int BATCH = 500;

    private final DebtorRepository debtors;
    private final PersonalDataIndex index;

    DocumentRootBackfill(DebtorRepository debtors, PersonalDataIndex index) {
        this.debtors = debtors;
        this.index = index;
    }

    /** @return devedores preenchidos neste lote (menor que {@link #BATCH} = terminou) */
    @Transactional
    int backfillBatch() {
        List<Debtor> pending = debtors.findCompaniesWithoutDocumentRoot(BATCH);
        int filled = 0;
        for (Debtor debtor : pending) {
            if (debtor.document().map(d -> debtor.assignDocumentRoot(index.documentRoot(d))).orElse(false)) {
                debtors.save(debtor);
                filled++;
            }
        }
        return filled;
    }
}
