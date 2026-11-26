package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import br.com.gestorcobranca.shared.domain.Document;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Troca do identificador com CPF/CNPJ embutido (SEG-20261125-K02, decisão 1476): os títulos e os contratos de que o
 * titular anonimizado é o devedor passam a {@code anon:} + HMAC, com a forma mascarada guardada para exibição. Só o
 * identificador que tem documento ({@code Document.maskIfDocument} diferente do valor) é trocado; contrato de outro
 * devedor em que ele era coobrigado fica como está (o identificador é do titular do contrato).
 *
 * <p>Na transação do chamador ({@code MANDATORY}), com o devedor já anonimizado e gravado (o gatilho do banco exige):
 * a anonimização do devedor e o preenchimento dos já anonimizados. Trava na ordem da carga (contrato → título, por id).
 */
@Component
class ExternalIdAnonymizer {

    static final String ORIGIN_ERASURE = "erasure";
    static final String ORIGIN_BACKFILL = "backfill";

    /** Quantos identificadores foram trocados; nunca o valor. */
    record Result(int titles, int contracts) {
        boolean isEmpty() {
            return titles == 0 && contracts == 0;
        }
    }

    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final ExternalIdDigest digest;
    private final DebtMetrics metrics;

    ExternalIdAnonymizer(DebtTitleRepository titles, OriginContractRepository contracts, ExternalIdDigest digest,
                         DebtMetrics metrics) {
        this.titles = titles;
        this.contracts = contracts;
        this.digest = digest;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Result anonymize(UUID tenantId, UUID debtorId, String origin) {
        int contractsReplaced = 0;
        // só os ids: a entidade é carregada pela primeira vez já travada (estado relido depois da espera)
        for (UUID id : contracts.findIdsByDebtorId(debtorId)) {
            OriginContract c = contracts.findByIdForUpdate(id).orElseThrow();
            String raw = c.getExternalId();
            if (needsReplacement(raw)
                    && c.anonymizeExternalId(digest.anonymized(tenantId, Kind.CONTRACT, c.getCreditorId(), raw),
                    AnonymizedSubjectQueryService.display(raw))) {
                contracts.save(c);
                contractsReplaced++;
            }
        }
        int titlesReplaced = 0;
        List<UUID> titleIds = titles.findIdsByDebtorId(debtorId);
        if (!titleIds.isEmpty()) {
            // em ordem de id (a consulta ordena)
            for (DebtTitle t : titles.findAllByIdInForUpdate(titleIds)) {
                String raw = t.getExternalId();
                if (needsReplacement(raw)
                        && t.anonymizeExternalId(digest.anonymized(tenantId, Kind.TITLE, t.getCreditorId(), raw),
                        AnonymizedSubjectQueryService.display(raw))) {
                    titles.save(t);
                    titlesReplaced++;
                }
            }
        }
        metrics.externalIdsAnonymized("title", origin, titlesReplaced);
        metrics.externalIdsAnonymized("contract", origin, contractsReplaced);
        return new Result(titlesReplaced, contractsReplaced);
    }

    /** Ainda cru e com CPF/CNPJ embutido (o detector atual; a busca da carga não depende dele). */
    static boolean needsReplacement(String raw) {
        return raw != null && !ExternalIdDigest.isAnonymized(raw) && !Document.maskIfDocument(raw).equals(raw);
    }
}
