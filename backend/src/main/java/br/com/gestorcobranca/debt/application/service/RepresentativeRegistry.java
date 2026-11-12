package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.CompanyRepresentative;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.RepresentativeRole;
import br.com.gestorcobranca.debt.domain.repository.CompanyRepresentativeRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Inclusão e atualização de representantes da empresa, comum ao cadastro manual e à carga, casando pelo blind index
 * do CPF entre os vigentes da empresa. Toda alteração é auditada com nome e CPF mascarados (a trilha é imutável e
 * sobrevive à anonimização).
 */
@Component
class RepresentativeRegistry {

    /** Resultado da gravação: o representante e se ele foi criado (falso = atualizado ou sem mudança). */
    record Written(CompanyRepresentative representative, boolean created, boolean changed) {
    }

    private final CompanyRepresentativeRepository representatives;
    private final PersonalDataIndex index;
    private final AuditTrail auditTrail;

    RepresentativeRegistry(CompanyRepresentativeRepository representatives, PersonalDataIndex index,
                           AuditTrail auditTrail) {
        this.representatives = representatives;
        this.index = index;
        this.auditTrail = auditTrail;
    }

    Optional<CompanyRepresentative> findActive(Debtor company, Document cpf) {
        return representatives.findByCompanyDebtorIdAndDocumentHashAndEndedAtIsNull(company.getId(),
                index.representative(cpf));
    }

    /** Novo representante; o chamador garante que o CPF ainda não é representante vigente da empresa. */
    CompanyRepresentative create(Debtor company, Document cpf, String name, RepresentativeRole role, boolean canNegotiate,
                                 LocalDate validUntil, String evidenceNote, CompanyRepresentative.Source source) {
        CompanyRepresentative r = representatives.save(new CompanyRepresentative(company, cpf, index.representative(cpf),
                name, role, canNegotiate, validUntil, evidenceNote, source));
        auditTrail.record(AuditEntry.of("company_representative.created", "company_representative", r.getId())
                .created("companyDebtorId", company.getId())
                .created("document", r.maskedDocument())
                .created("name", DebtViews.maskName(r.getName()))
                .created("role", r.getRole())
                .created("canNegotiate", r.isCanNegotiate())
                .created("validUntil", r.getValidUntil())
                .created("evidenceNote", r.getEvidenceNote() != null)
                .meta("source", source));
        return r;
    }

    /** Atualização auditada (antes e depois do que mudou). @return verdadeiro se algo mudou */
    boolean update(CompanyRepresentative r, String name, RepresentativeRole role, boolean canNegotiate,
                   LocalDate validUntil, String evidenceNote, CompanyRepresentative.Source source) {
        String previousName = r.getName();
        RepresentativeRole previousRole = r.getRole();
        boolean previousNegotiate = r.isCanNegotiate();
        LocalDate previousValidUntil = r.getValidUntil();
        String previousEvidence = r.getEvidenceNote();
        r.update(name, role, canNegotiate, validUntil, evidenceNote);
        boolean changed = !previousName.equals(r.getName()) || previousRole != r.getRole()
                || previousNegotiate != r.isCanNegotiate() || !Objects.equals(previousValidUntil, r.getValidUntil())
                || !Objects.equals(previousEvidence, r.getEvidenceNote());
        if (!changed) {
            return false;
        }
        representatives.save(r);
        AuditEntry audit = AuditEntry.of("company_representative.updated", "company_representative", r.getId())
                .meta("companyDebtorId", r.getCompanyDebtorId()).meta("source", source);
        if (!previousName.equals(r.getName())) {
            audit.change("name", DebtViews.maskName(previousName), DebtViews.maskName(r.getName()));
        }
        if (previousRole != r.getRole()) {
            audit.change("role", previousRole, r.getRole());
        }
        if (previousNegotiate != r.isCanNegotiate()) {
            audit.change("canNegotiate", previousNegotiate, r.isCanNegotiate());
        }
        if (!Objects.equals(previousValidUntil, r.getValidUntil())) {
            audit.change("validUntil", previousValidUntil, r.getValidUntil());
        }
        if (!Objects.equals(previousEvidence, r.getEvidenceNote())) {
            audit.meta("evidenceNoteChanged", true);
        }
        auditTrail.record(audit);
        return true;
    }

    /**
     * Representante da carga: casado pelo CPF entre os vigentes (nome atualizado; papel e poder só quando informados;
     * validade e evidência ficam com o cadastro manual); CPF novo → novo representante (papel OTHER e sem poder quando
     * não informados).
     *
     * @param role         nulo = não informado (mantém o atual)
     * @param canNegotiate nulo = não informado (mantém o atual)
     */
    Written upsertFromImport(Debtor company, Document cpf, String name, RepresentativeRole role, Boolean canNegotiate) {
        Optional<CompanyRepresentative> existing = findActive(company, cpf);
        if (existing.isEmpty()) {
            return new Written(create(company, cpf, name, role == null ? RepresentativeRole.OTHER : role,
                    Boolean.TRUE.equals(canNegotiate), null, null, CompanyRepresentative.Source.IMPORT), true, true);
        }
        CompanyRepresentative r = existing.get();
        boolean changed = update(r, name, role == null ? r.getRole() : role,
                canNegotiate == null ? r.isCanNegotiate() : canNegotiate, r.getValidUntil(), r.getEvidenceNote(),
                CompanyRepresentative.Source.IMPORT);
        return new Written(r, false, changed);
    }
}
