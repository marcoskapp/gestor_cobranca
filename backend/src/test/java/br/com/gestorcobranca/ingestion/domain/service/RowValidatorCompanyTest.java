package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static br.com.gestorcobranca.ingestion.domain.model.CanonicalField.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Colunas da cobrança B2B (nome fantasia, pessoa de contato, representantes) — dados fictícios. */
class RowValidatorCompanyTest {

    private final RowValidator validator = new RowValidator(LocalDate.of(2026, 10, 23));

    private static Map<CanonicalField, String> company() {
        Map<CanonicalField, String> values = new EnumMap<>(CanonicalField.class);
        values.put(DEBTOR_DOCUMENT, "60.012.345/0001-97");
        values.put(DEBTOR_NAME, "Comercial Fictícia Ltda");
        values.put(TITLE_EXTERNAL_ID, "T-1");
        values.put(DUE_DATE, "10/03/2026");
        values.put(PRINCIPAL_AMOUNT, "1.234,56");
        return values;
    }

    private RowValidation validate(Map<CanonicalField, String> values) {
        return validator.validate(new SourceRow(2, values), null);
    }

    @Test
    void companyRowCarriesTradeNameContactPersonsAndRepresentatives() {
        Map<CanonicalField, String> values = company();
        values.put(TRADE_NAME, "  Padaria   Fictícia ");
        values.put(PHONE_1, "(11) 98765-4321");
        values.put(PHONE_1_PERSON, "Ana Fictícia");
        values.put(PHONE_1_PERSON_ROLE, "Financeiro");
        values.put(EMAIL_1, "contas@ficticia.com.br");
        values.put(EMAIL_1_PERSON_ROLE, "contas a pagar");
        values.put(REPRESENTATIVE_1_DOCUMENT, "123.456.789-09");
        values.put(REPRESENTATIVE_1_NAME, "João Sócio");
        values.put(REPRESENTATIVE_1_ROLE, "sócio administrador");
        values.put(REPRESENTATIVE_1_NEGOTIATES, "S");
        values.put(REPRESENTATIVE_2_DOCUMENT, "98765432100");
        values.put(REPRESENTATIVE_2_NAME, "Maria Procuradora");
        values.put(REPRESENTATIVE_2_ROLE, "PROCURADOR");
        RowValidation result = validate(values);
        ParsedRow row = result.valid().orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(row.tradeName()).isEqualTo("Padaria Fictícia");
        assertThat(row.contacts().getFirst().personName()).isEqualTo("Ana Fictícia");
        assertThat(row.contacts().getFirst().personRole()).isEqualTo("FINANCE");
        assertThat(row.contacts().get(1).personRole()).isEqualTo("ACCOUNTS_PAYABLE");
        assertThat(row.representatives()).extracting(ParsedRow.ParsedRepresentative::role)
                .containsExactly("MANAGING_PARTNER", "ATTORNEY_IN_FACT");
        // "negocia" sem coluna = não informado (nulo): a carga não apaga o poder cadastrado à mão
        assertThat(row.representatives()).extracting(ParsedRow.ParsedRepresentative::canNegotiate)
                .containsExactly(true, null);
    }

    @Test
    void representativeRoleAndPowerNotInformedStayNullAndExplicitNoIsFalse() {
        Map<CanonicalField, String> values = company();
        values.put(REPRESENTATIVE_1_DOCUMENT, "123.456.789-09");
        values.put(REPRESENTATIVE_1_NAME, "João Sócio");
        values.put(REPRESENTATIVE_2_DOCUMENT, "98765432100");
        values.put(REPRESENTATIVE_2_NAME, "Maria Procuradora");
        values.put(REPRESENTATIVE_2_NEGOTIATES, "N");
        RowValidation result = validate(values);
        ParsedRow row = result.valid().orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(row.representatives()).extracting(ParsedRow.ParsedRepresentative::role).containsExactly(null, null);
        assertThat(row.representatives()).extracting(ParsedRow.ParsedRepresentative::canNegotiate)
                .containsExactly(null, false);
    }

    @Test
    void complementaryCompanyDataProblemsAreWarningsAndTheDataIsIgnored() {
        Map<CanonicalField, String> values = company();
        values.put(BIRTH_DATE, "01/01/1990");
        values.put(PHONE_2_PERSON, "Sem telefone");
        values.put(PHONE_1, "(11) 98765-4321");
        values.put(PHONE_1_PERSON_ROLE, "Irmã");
        values.put(REPRESENTATIVE_1_DOCUMENT, "60.012.345/0001-97");
        values.put(REPRESENTATIVE_1_NAME, "Empresa");
        values.put(REPRESENTATIVE_2_DOCUMENT, "123.456.789-09");
        RowValidation result = validate(values);
        ParsedRow row = result.valid().orElseThrow();
        assertThat(result.issues()).allMatch(i -> !i.isError()).extracting(RowIssue::code).containsExactlyInAnyOrder(
                "row.birth_date_company", "row.contact_person_without_contact", "row.contact_person_role_invalid",
                "row.representative_document_invalid", "row.representative_incomplete");
        assertThat(row.birthDate()).isNull();
        assertThat(row.contacts().getFirst().personRole()).isNull();
        assertThat(row.representatives()).isEmpty();
    }

    @Test
    void individualRowIgnoresTradeNameAndRepresentativesWithWarning() {
        Map<CanonicalField, String> values = company();
        values.put(DEBTOR_DOCUMENT, "529.982.247-25");
        values.put(TRADE_NAME, "Loja");
        values.put(REPRESENTATIVE_1_DOCUMENT, "123.456.789-09");
        values.put(REPRESENTATIVE_1_NAME, "João");
        RowValidation result = validate(values);
        assertThat(result.issues()).extracting(RowIssue::code)
                .containsExactlyInAnyOrder("row.trade_name_individual", "row.representative_individual");
        assertThat(result.valid().orElseThrow().tradeName()).isNull();
    }

    @Test
    void formulaInPersonNamesIsRejected() {
        Map<CanonicalField, String> values = company();
        values.put(PHONE_1, "(11) 98765-4321");
        values.put(PHONE_1_PERSON, "=HYPERLINK(\"x\")");
        assertThat(validate(values).errors()).extracting(RowIssue::code).contains("row.formula_not_allowed");
    }
}
