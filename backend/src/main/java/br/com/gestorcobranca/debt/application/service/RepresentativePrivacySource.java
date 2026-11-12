package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.CompanyRepresentative;
import br.com.gestorcobranca.debt.domain.repository.CompanyRepresentativeRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.privacy.DataSharingLink;
import br.com.gestorcobranca.shared.privacy.RelatedPersonSource;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Representantes de empresas devedoras como titulares (LGPD): localizados pelo blind index do CPF (finalidade própria
 * do representante), sem devolver nome nem CPF. A ficha da empresa é a evidência (seção Representantes).
 */
@Component
class RepresentativePrivacySource implements RelatedPersonSource {

    static final String RELATION = "COMPANY_REPRESENTATIVE";

    private final CompanyRepresentativeRepository representatives;
    private final DebtTitleRepository titles;
    private final PersonalDataIndex index;

    RepresentativePrivacySource(CompanyRepresentativeRepository representatives, DebtTitleRepository titles,
                                PersonalDataIndex index) {
        this.representatives = representatives;
        this.titles = titles;
        this.index = index;
    }

    @Override
    public String id() {
        return "debt";
    }

    @Override
    public List<RelatedPersonLink> linksOf(String cpf, Set<UUID> creditorScope) {
        Document document;
        try {
            document = Document.parse(cpf);
        } catch (BusinessRuleException e) {
            return List.of();
        }
        if (document.type() != Document.DocumentType.CPF) {
            return List.of();
        }
        return representatives.findByDocumentHashOrderByCreatedAtAsc(index.representative(document)).stream()
                .filter(r -> creditorScope == null
                        || titles.findCreditorIdsByDebtorId(r.getCompanyDebtorId()).stream().anyMatch(creditorScope::contains))
                .map(RepresentativePrivacySource::link)
                .toList();
    }

    private static RelatedPersonLink link(CompanyRepresentative r) {
        return new RelatedPersonLink(RELATION, r.getCompanyDebtorId(), r.getRole().name(), "nome, CPF e papel na empresa",
                r.getCreatedAt(), r.getEndedAt(),
                DataSharingLink.to("/devedores/" + r.getCompanyDebtorId(), "Abrir a empresa", Permission.DEBTOR_VIEW));
    }
}
