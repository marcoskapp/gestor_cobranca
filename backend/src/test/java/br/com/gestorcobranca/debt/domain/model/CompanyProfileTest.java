package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cobrança B2B no domínio: raiz do CNPJ, nome fantasia, pessoa de contato e representantes. Dados fictícios. */
class CompanyProfileTest {

    private static final Document HEAD_OFFICE = Document.parse("60.012.345/0001-97");
    private static final Document BRANCH = Document.parse("60.012.345/0002-78");
    private static final Document CPF = Document.parse("123.456.789-09");

    private static Debtor company(Document cnpj) {
        Debtor d = new Debtor(cnpj, "h", "root", "Comercial Fictícia Ltda", "Padaria Fictícia", null, null);
        setId(d);
        return d;
    }

    private static void setId(Object entity) {
        try {
            var field = br.com.gestorcobranca.shared.domain.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void cnpjRootAndEstablishment() {
        assertThat(HEAD_OFFICE.cnpjRoot()).contains("60012345");
        assertThat(BRANCH.cnpjRoot()).isEqualTo(HEAD_OFFICE.cnpjRoot());
        assertThat(BRANCH.cnpjBranch()).contains("0002");
        assertThat(HEAD_OFFICE.isHeadOffice()).isTrue();
        assertThat(CPF.cnpjRoot()).isEmpty();
        assertThat(company(HEAD_OFFICE).establishmentLabel()).isEqualTo("Matriz");
        assertThat(company(BRANCH).establishmentLabel()).isEqualTo("Filial 0002");
    }

    @Test
    void tradeNameAndBirthDateFollowThePersonType() {
        Debtor company = company(HEAD_OFFICE);
        assertThat(company.getTradeName()).isEqualTo("Padaria Fictícia");
        assertThat(company.getDocumentRootHash()).isEqualTo("root");
        assertThatThrownBy(() -> company.update("Comercial", "Padaria", LocalDate.of(1990, 1, 1), null))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "debtor.birth_date_company");
        // carga com data de nascimento de empresa: ignorada; nome fantasia vazio mantém o atual
        assertThat(company.updateFromImport("Comercial Fictícia Ltda", " ", LocalDate.of(1990, 1, 1))).isFalse();
        assertThat(company.getBirthDate()).isNull();

        Debtor person = new Debtor(Document.parse("529.982.247-25"), "h", "ignorada", "Maria", null, null, null);
        assertThat(person.getDocumentRootHash()).as("CPF não tem raiz").isNull();
        assertThatThrownBy(() -> person.update("Maria", "Loja", null, null))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "debtor.trade_name_individual");
        assertThat(person.establishmentLabel()).isNull();

        company.anonymize(Instant.now());
        assertThat(company.getTradeName()).isNull();
        assertThat(company.getDocumentRootHash()).isNull();
    }

    @Test
    void documentRootIsAssignedOnlyOnceAndOnlyToCompanies() {
        Debtor older = new Debtor(HEAD_OFFICE, "h", "Comercial", null, null);
        assertThat(older.assignDocumentRoot("root")).isTrue();
        assertThat(older.assignDocumentRoot("other")).isFalse();
        assertThat(older.getDocumentRootHash()).isEqualTo("root");
    }

    @Test
    void contactPersonRequiresSomeoneToBeTheDecisionMaker() {
        DebtorContact contact = new DebtorContact(UUID.randomUUID(), ContactValue.parse(ContactType.PHONE, "11987654321"),
                "h", ContactLabel.WORK, true, ContactSource.MANUAL, 0);
        contact.assignPerson("  Ana   Fictícia ", ContactPersonRole.FINANCE, true);
        assertThat(contact.getPersonName()).isEqualTo("Ana Fictícia");
        assertThat(contact.hasPerson()).isTrue();
        assertThatThrownBy(() -> contact.assignPerson(null, null, true))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "contact.decision_maker_without_person");
        contact.assignPerson(" ", null, false);
        assertThat(contact.hasPerson()).isFalse();
    }

    @Test
    void representativeIsAlwaysAPersonAndNegotiatesOnlyWhileCurrent() {
        Debtor company = company(HEAD_OFFICE);
        CompanyRepresentative r = new CompanyRepresentative(company, CPF, "h", "João Sócio",
                RepresentativeRole.MANAGING_PARTNER, true, LocalDate.of(2026, 12, 31), "contrato social de 2024",
                CompanyRepresentative.Source.MANUAL);
        assertThat(r.canNegotiateOn(LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(r.canNegotiateOn(LocalDate.of(2027, 1, 1))).isFalse();
        assertThat(r.maskedDocument()).isEqualTo("***.456.789-**");

        r.update("João Sócio", RepresentativeRole.MANAGING_PARTNER, false, null, null);
        assertThat(r.canNegotiateOn(LocalDate.of(2027, 1, 1))).isFalse();
        r.update("João Sócio", RepresentativeRole.MANAGING_PARTNER, true, null, null);
        r.end("Saiu da sociedade", Instant.now());
        assertThat(r.canNegotiateOn(LocalDate.of(2026, 1, 1))).isFalse();
        assertThatThrownBy(() -> r.update("João", RepresentativeRole.OTHER, true, null, null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "representative.ended");

        assertThatThrownBy(() -> new CompanyRepresentative(company, BRANCH, "h", "Empresa", RepresentativeRole.OTHER, true,
                null, null, CompanyRepresentative.Source.MANUAL))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "representative.cpf_required");
        Debtor person = new Debtor(Document.parse("529.982.247-25"), "h", "Maria", null, null);
        assertThatThrownBy(() -> new CompanyRepresentative(person, CPF, "h", "João", RepresentativeRole.OTHER, true,
                null, null, CompanyRepresentative.Source.MANUAL))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "representative.company_required");
    }
}
