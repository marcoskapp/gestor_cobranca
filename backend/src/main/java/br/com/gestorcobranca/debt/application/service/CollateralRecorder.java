package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.AssetIdentification;
import br.com.gestorcobranca.debt.domain.model.Collateral;
import br.com.gestorcobranca.debt.domain.model.CollateralAppraisal;
import br.com.gestorcobranca.debt.domain.model.CollateralStatus;
import br.com.gestorcobranca.debt.domain.model.CollateralType;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.CollateralAppraisalRepository;
import br.com.gestorcobranca.debt.domain.repository.CollateralRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gravação de garantias comum ao cadastro manual e à carga: unicidade do bem no contrato,
 * vínculo com títulos do contrato, avaliações, situação, auditoria e eventos de negócio — na
 * transação do chamador, que já validou o escopo.
 */
@Component
class CollateralRecorder {

    static final String ENTITY = "collateral";

    private final CollateralRepository collaterals;
    private final CollateralAppraisalRepository appraisals;
    private final DebtTitleRepository titles;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    CollateralRecorder(CollateralRepository collaterals, CollateralAppraisalRepository appraisals,
                       DebtTitleRepository titles, AuditTrail auditTrail, BusinessEventLogger businessEvents, Clock clock) {
        this.collaterals = collaterals;
        this.appraisals = appraisals;
        this.titles = titles;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    /** Dados do registro do gravame (todos opcionais). */
    record Lien(String authority, String number, LocalDate date) {
    }

    Collateral register(OriginContract contract, CollateralType type, String externalId, String description,
                        AssetIdentification asset, Lien lien, Collection<UUID> titleIds, String source) {
        Collateral collateral = new Collateral(contract.getCreditorId(), contract.getId(), externalId, type, description, asset);
        if (collateral.getExternalId() != null
                && collaterals.findByOriginContractIdAndExternalId(contract.getId(), collateral.getExternalId()).isPresent()) {
            throw new ConflictException("collateral.duplicate", "Já existe garantia com este identificador no contrato");
        }
        requireAssetNotTaken(contract.getId(), asset, null);
        collateral.registerLien(lien.authority(), lien.number(), lien.date());
        collateral.coverTitles(validTitles(contract, titleIds));
        collaterals.save(collateral);
        auditTrail.record(AuditEntry.of("collateral.created", ENTITY, collateral.getId())
                .creditor(collateral.getCreditorId())
                .created("originContractId", contract.getId())
                .created("type", collateral.getType())
                .created("asset", collateral.getAsset().summary())
                .created("registrationNumber", collateral.getRegistrationNumber())
                .created("titleIds", sorted(collateral.getTitleIds()))
                .meta("source", source));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COLLATERAL_REGISTERED)
                .creditor(contract.getCreditorId()).portfolio(contract.getPortfolioId()).debtor(contract.getDebtorId())
                .reference(ENTITY, collateral.getId())
                .attr("type", collateral.getType()).attr("assetKind", collateral.getAsset().getKind())
                .attr("originContractId", contract.getId()).attr("source", source));
        return collateral;
    }

    /** @return true se algo mudou */
    boolean update(OriginContract contract, Collateral collateral, String description, AssetIdentification asset,
                   Lien lien, Collection<UUID> titleIds, String source) {
        String previousAsset = collateral.getAsset().summary();
        String previousDescription = collateral.getDescription();
        String previousRegistration = collateral.getRegistrationNumber();
        String previousAuthority = collateral.getRegistrationAuthority();
        LocalDate previousRegistrationDate = collateral.getRegistrationDate();
        List<UUID> previousTitles = sorted(collateral.getTitleIds());
        if (!asset.sameAs(collateral.getAsset())) {
            requireAssetNotTaken(contract.getId(), asset, collateral.getId());
        }
        boolean changed = collateral.describe(description, asset);
        changed |= collateral.registerLien(lien.authority(), lien.number(), lien.date());
        changed |= collateral.coverTitles(validTitles(contract, titleIds));
        if (changed) {
            collaterals.save(collateral);
            auditTrail.record(AuditEntry.of("collateral.updated", ENTITY, collateral.getId())
                    .creditor(collateral.getCreditorId())
                    .change("asset", previousAsset, collateral.getAsset().summary())
                    .change("description", previousDescription, collateral.getDescription())
                    .change("registrationAuthority", previousAuthority, collateral.getRegistrationAuthority())
                    .change("registrationNumber", previousRegistration, collateral.getRegistrationNumber())
                    .change("registrationDate", previousRegistrationDate, collateral.getRegistrationDate())
                    .change("titleIds", previousTitles.toString(), sorted(collateral.getTitleIds()).toString())
                    .meta("source", source));
        }
        return changed;
    }

    CollateralStatus changeStatus(OriginContract contract, Collateral collateral, CollateralStatus target, String reason) {
        CollateralStatus previous = collateral.changeStatus(target, reason, Instant.now(clock));
        collaterals.save(collateral);
        auditTrail.record(AuditEntry.of("collateral.status_changed", ENTITY, collateral.getId())
                .creditor(collateral.getCreditorId()).reason(collateral.getStatusReason())
                .change("status", previous, collateral.getStatus()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COLLATERAL_STATUS_CHANGED)
                .creditor(contract.getCreditorId()).portfolio(contract.getPortfolioId()).debtor(contract.getDebtorId())
                .reference(ENTITY, collateral.getId()).amount(collateral.getEstimatedValue())
                .attr("type", collateral.getType()).attr("from", previous).attr("to", collateral.getStatus()));
        return previous;
    }

    CollateralAppraisal appraise(OriginContract contract, Collateral collateral, CollateralAppraisal appraisal,
                                 String source) {
        appraisals.save(appraisal);
        java.math.BigDecimal previousEstimate = collateral.getEstimatedValue() == null ? null
                : collateral.getEstimatedValue().amount();
        if (collateral.recordAppraisal(appraisal)) {
            collaterals.save(collateral);
        }
        auditTrail.record(AuditEntry.of("collateral.appraised", ENTITY, collateral.getId())
                .creditor(collateral.getCreditorId())
                .change("estimatedValue", previousEstimate == null ? null : previousEstimate.toPlainString(),
                        collateral.getEstimatedValue() == null ? null : collateral.getEstimatedValue().amount().toPlainString())
                .meta("appraisalId", appraisal.getId())
                .meta("amount", appraisal.getAmount())
                .meta("appraisalDate", appraisal.getAppraisalDate())
                .meta("appraisalSource", appraisal.getSource())
                .meta("importBatchId", appraisal.getImportBatchId())
                .meta("source", source));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COLLATERAL_APPRAISED)
                .creditor(contract.getCreditorId()).portfolio(contract.getPortfolioId()).debtor(contract.getDebtorId())
                .reference(ENTITY, collateral.getId()).amount(appraisal.getAmount())
                .attr("type", collateral.getType()).attr("appraisalSource", appraisal.getSource())
                .attr("appraisalDate", appraisal.getAppraisalDate().toString())
                .attr("estimatedValue", collateral.getEstimatedValue() == null ? null : collateral.getEstimatedValue().amount()));
        return appraisal;
    }

    private void requireAssetNotTaken(UUID contractId, AssetIdentification asset, UUID self) {
        collaterals.findSameAsset(contractId, asset).filter(c -> !c.getId().equals(self)).ifPresent(c -> {
            throw new ConflictException("collateral.duplicate", "Este bem já está cadastrado como garantia do contrato");
        });
    }

    private Set<UUID> validTitles(OriginContract contract, Collection<UUID> titleIds) {
        if (titleIds == null || titleIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> requested = Set.copyOf(titleIds);
        List<DebtTitle> found = titles.findAllByIdIn(requested);
        boolean allInContract = found.size() == requested.size()
                && found.stream().allMatch(t -> contract.getId().equals(t.getOriginContractId()));
        if (!allInContract) {
            throw new BusinessRuleException("collateral.title_not_in_contract",
                    "Os títulos cobertos devem ser parcelas do contrato da garantia");
        }
        return requested;
    }

    private static List<UUID> sorted(Set<UUID> ids) {
        return ids.stream().sorted().toList();
    }
}
