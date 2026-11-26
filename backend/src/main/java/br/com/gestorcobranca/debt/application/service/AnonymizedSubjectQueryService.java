package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.spi.ErasedSubjects;
import br.com.gestorcobranca.debt.application.port.spi.ErasedSubjects.ReturnAttempt;
import br.com.gestorcobranca.debt.application.port.in.AnonymizedSubjectQueries;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class AnonymizedSubjectQueryService implements AnonymizedSubjectQueries {

    private static final String RESOURCE = "debtor";

    private final DebtorRepository debtors;
    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final AccessScope accessScope;
    private final ErasedSubjects erasedSubjects;
    private final ObligationPartyRepository parties;
    private final ExternalIdLookup lookup;
    private final ExternalIdDigest digest;
    private final CurrentActor currentActor;

    AnonymizedSubjectQueryService(DebtorRepository debtors, DebtTitleRepository titles,
                                  OriginContractRepository contracts, AccessScope accessScope,
                                  ErasedSubjects erasedSubjects, ObligationPartyRepository parties,
                                  ExternalIdLookup lookup, ExternalIdDigest digest, CurrentActor currentActor) {
        this.parties = parties;
        this.lookup = lookup;
        this.digest = digest;
        this.currentActor = currentActor;
        this.debtors = debtors;
        this.titles = titles;
        this.contracts = contracts;
        this.accessScope = accessScope;
        this.erasedSubjects = erasedSubjects;
    }

    @Override
    public Set<Document> erasedSubjectsAmong(UUID creditorId, UUID batchId, Map<Document, Integer> linesByDocument) {
        accessScope.requirePermission(Permission.IMPORT_EXECUTE);
        accessScope.requireCreditor(creditorId);
        if (linesByDocument == null || linesByDocument.isEmpty()) {
            return Set.of();
        }
        return erasedSubjects.erasedAmong(linesByDocument, ReturnAttempt.importBatch(creditorId, batchId));
    }

    @Override
    public List<TitleReference> referencesOf(UUID debtorId) {
        accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
        if (debtors.findById(debtorId).filter(Debtor::isAnonymized).isEmpty()) {
            return List.of();
        }
        return titles.findByDebtor(debtorId, null).stream()
                .map(t -> new TitleReference(t.getCreditorId(), t.getPortfolioId(), titleKey(t), display(t),
                        t.getInstallmentNumber()))
                .toList();
    }

    @Override
    public List<ContractReference> coObligorContractsOf(UUID debtorId) {
        accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
        if (debtors.findById(debtorId).filter(Debtor::isAnonymized).isEmpty()) {
            return List.of();
        }
        List<UUID> contractIds = parties.findByDebtorIdOrderByCreatedAtAsc(debtorId).stream()
                .map(ObligationParty::getOriginContractId).distinct().toList();
        if (contractIds.isEmpty()) {
            return List.of();
        }
        return contracts.findAllByIdIn(contractIds).stream()
                .filter(c -> !debtorId.equals(c.getDebtorId()))
                .map(c -> new ContractReference(c.getCreditorId(), c.getPortfolioId(), display(c)))
                .toList();
    }

    @Override
    public AnonymizedKeys anonymizedKeys(UUID creditorId, Collection<String> titleExternalIds,
                                         Collection<String> contractExternalIds) {
        accessScope.requirePermission(Permission.IMPORT_EXECUTE);
        accessScope.requireCreditor(creditorId);
        // como a carga: identificador sem espaços nas pontas, nulos e vazios fora; pelas duas formas gravadas (1476)
        return new AnonymizedKeys(lookup.anonymizedTitleKeys(creditorId, titleExternalIds),
                lookup.anonymizedContractKeys(creditorId, contractExternalIds));
    }

    /** Chave HMAC do título: a da forma já trocada, ou calculada do identificador ainda cru. */
    private String titleKey(DebtTitle t) {
        String sealed = ExternalIdDigest.keyOfAnonymized(t.getExternalId());
        return sealed != null ? sealed
                : digest.key(currentActor.tenantId(), Kind.TITLE, t.getCreditorId(), t.getExternalId());
    }

    /** Forma mascarada: a guardada na troca (decisão 1476) ou calculada do identificador ainda cru. */
    private static String display(DebtTitle t) {
        return ExternalIdDigest.isAnonymized(t.getExternalId()) ? t.displayExternalId() : display(t.getExternalId());
    }

    private static String display(OriginContract c) {
        return ExternalIdDigest.isAnonymized(c.getExternalId()) ? c.displayExternalId() : display(c.getExternalId());
    }

    /** Referência para exibição a terceiros: documento mascarado e sem caracteres de controle (vai para texto copiado). */
    static String display(String externalId) {
        return Document.maskIfDocument(externalId).replaceAll("\\p{Cntrl}", " ").strip();
    }

    @Override
    public Set<UUID> anonymizedDebtorsOf(UUID creditorId, Collection<String> titleExternalIds,
                                         Collection<String> contractExternalIds) {
        accessScope.requirePermission(Permission.IMPORT_EXECUTE);
        accessScope.requireCreditor(creditorId);
        return lookup.anonymizedDebtorIds(creditorId, titleExternalIds, contractExternalIds);
    }
}
