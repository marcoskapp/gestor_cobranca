package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow.ContactKind;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.model.TitleSituation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

import static br.com.gestorcobranca.ingestion.domain.model.CanonicalField.*;
import static org.assertj.core.api.Assertions.assertThat;

class RowValidatorTest {

    private final RowValidator validator = new RowValidator(LocalDate.of(2026, 10, 23));

    private static Map<CanonicalField, String> valid() {
        Map<CanonicalField, String> values = new EnumMap<>(CanonicalField.class);
        values.put(DEBTOR_DOCUMENT, "529.982.247-25");
        values.put(DEBTOR_NAME, "  Maria   da Silva ");
        values.put(TITLE_EXTERNAL_ID, "T-1");
        values.put(DUE_DATE, "10/03/2026");
        values.put(PRINCIPAL_AMOUNT, "1.234,56");
        return values;
    }

    private RowValidationAssert validate(Map<CanonicalField, String> values) {
        return new RowValidationAssert(validator.validate(new SourceRow(7, values), null));
    }

    private record RowValidationAssert(RowValidation validation) {
        boolean hasCode(String code) {
            return validation.issues().stream().map(RowIssue::code).anyMatch(code::equals);
        }
    }

    @Test
    void convertsAValidRow() {
        Map<CanonicalField, String> values = valid();
        values.put(PHONE_1, "(11) 98765-4321");
        values.put(WHATSAPP_1, "S");
        values.put(EMAIL_1, "maria@exemplo.com");
        values.put(ADDRESS_STREET, "Rua A");
        values.put(ADDRESS_NUMBER, "10");
        values.put(ADDRESS_CITY, "São Paulo");
        values.put(ADDRESS_STATE, "sp");
        values.put(ADDRESS_ZIP, "01001000");
        values.put(CONTRACT_NUMBER, "C-9");
        values.put(REPORTED_BALANCE, "1300,00");
        values.put(BALANCE_REFERENCE_DATE, "2026-10-01");
        RowValidation result = validator.validate(new SourceRow(7, values), null);
        ParsedRow row = result.valid().orElseThrow();
        assertThat(result.issues()).isEmpty();
        assertThat(row.name()).isEqualTo("Maria da Silva");
        assertThat(row.principalAmount()).isEqualByComparingTo("1234.56");
        assertThat(row.dueDate()).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(row.situation()).isEqualTo(TitleSituation.ACTIVE);
        assertThat(row.contacts()).extracting(ParsedRow.ParsedContact::kind)
                .containsExactly(ContactKind.PHONE, ContactKind.EMAIL, ContactKind.ADDRESS);
        assertThat(row.contacts().getFirst().whatsapp()).isTrue();
        assertThat(row.contacts().getLast().value()).isEqualTo("Rua A, 10 - São Paulo/SP - CEP 01001-000");
        assertThat(row.reportedBalance()).isEqualByComparingTo("1300.00");
    }

    @Test
    void missingRequiredFieldsAndInvalidIdentifiersAreErrors() {
        Map<CanonicalField, String> values = valid();
        values.remove(DEBTOR_NAME);
        values.put(DEBTOR_DOCUMENT, "111.111.111-11");
        values.put(DUE_DATE, "31/02/2026");
        values.put(PRINCIPAL_AMOUNT, "0");
        RowValidation result = validator.validate(new SourceRow(3, values), null);
        assertThat(result.valid()).isEmpty();
        assertThat(result.errors()).extracting(RowIssue::code).containsExactlyInAnyOrder("row.required_missing",
                "row.document_invalid", "row.due_date_invalid", "row.principal_zero");
        RowIssue document = result.errors().stream().filter(i -> i.code().equals("row.document_invalid")).findFirst().orElseThrow();
        assertThat(document.valueExcerpt()).isEqualTo("***.***.***-11");
        assertThat(document.rowNumber()).isEqualTo(3);
    }

    @Test
    void complementaryDataProblemsAreWarningsAndTheDataIsDropped() {
        Map<CanonicalField, String> values = valid();
        values.put(BIRTH_DATE, "2030-01-01");
        values.put(WHATSAPP_1, "talvez");
        values.put(PHONE_1, "11987654321");
        values.put(ADDRESS_CITY, "Campinas");
        values.put(REPORTED_BALANCE, "100,00");
        RowValidation result = validator.validate(new SourceRow(2, values), null);
        assertThat(result.valid()).isPresent();
        assertThat(result.warnings()).extracting(RowIssue::code).containsExactlyInAnyOrder("row.birth_date_invalid",
                "row.whatsapp_invalid", "row.address_incomplete", "row.balance_date_required");
        assertThat(result.valid().get().birthDate()).isNull();
        assertThat(result.valid().get().reportedBalance()).isNull();
    }

    @Test
    void duplicateTitleInTheSameFileIsAnError() {
        RowValidation result = validator.validate(new SourceRow(9, valid()), 4L);
        assertThat(result.errors()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("row.duplicate_title");
            assertThat(i.message()).contains("linha 4");
        });
    }

    @Test
    void parsesCreditorSituationAndRejectsUnknownValues() {
        Map<CanonicalField, String> values = valid();
        values.put(TITLE_SITUATION, "Contestado");
        assertThat(validator.validate(new SourceRow(2, values), null).valid().orElseThrow().situation())
                .isEqualTo(TitleSituation.DISPUTED);
        values.put(TITLE_SITUATION, "em negociação");
        assertThat(validate(values).hasCode("row.situation_invalid")).isTrue();
    }

    @Test
    void rejectsSpreadsheetFormulasInFreeTextFields() {
        Map<CanonicalField, String> values = valid();
        values.put(DEBTOR_NAME, "=HYPERLINK(\"http://x\",\"Clique\")");
        values.put(PRODUCT, "@SUM(A1)");
        values.put(CONTRACT_NUMBER, "C-1");
        values.put(PHONE_1, "+55 11 98765-4321");
        RowValidation result = validator.validate(new SourceRow(2, values), null);
        assertThat(result.errors()).extracting(RowIssue::code, RowIssue::field)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("row.formula_not_allowed", "nome"),
                        org.assertj.core.groups.Tuple.tuple("row.formula_not_allowed", "produto"));
    }

    @Test
    void personTypeMustMatchTheDocument() {
        Map<CanonicalField, String> values = valid();
        values.put(PERSON_TYPE, "J");
        assertThat(validate(values).hasCode("row.document_invalid") || validate(values).hasCode("row.person_type_mismatch"))
                .isTrue();
        values.put(PERSON_TYPE, "PF");
        assertThat(validate(values).validation().valid()).isPresent();
    }

    @Test
    void parsesLegalDataOfTheContract() {
        Map<CanonicalField, String> values = valid();
        values.put(CONTRACT_NUMBER, "C-1");
        values.put(INSTRUMENT_TYPE, "Nota Promissória");
        values.put(EXECUTIVE_TITLE, "S");
        values.put(CO_OBLIGOR_1_DOCUMENT, "111.444.777-35");
        values.put(CO_OBLIGOR_1_NAME, "João");
        values.put(CO_OBLIGOR_1_ROLE, "fiador");
        values.put(COLLATERAL_TYPE, "HIPOTECA");
        values.put(COLLATERAL_REGISTRY_NUMBER, "12.345");
        values.put(COLLATERAL_REGISTRY_OFFICE, "1º RI de Campinas");
        values.put(COLLATERAL_MUNICIPALITY, "Campinas");
        values.put(COLLATERAL_STATE, "sp");
        values.put(APPRAISAL_AMOUNT, "300.000,00");
        values.put(APPRAISAL_SOURCE, "avaliador");
        RowValidation result = validator.validate(new SourceRow(2, values), null);
        assertThat(result.issues()).isEmpty();
        var legal = result.valid().orElseThrow().legal();
        assertThat(legal.nature().instrumentType()).isEqualTo("PROMISSORY_NOTE");
        assertThat(legal.nature().executiveTitle()).isTrue();
        assertThat(legal.parties()).singleElement().satisfies(p -> assertThat(p.role()).isEqualTo("GUARANTOR_SURETY"));
        assertThat(legal.collateral().assetKind()).isEqualTo("REAL_ESTATE");
        assertThat(legal.collateral().state()).isEqualTo("SP");
        assertThat(legal.collateral().appraisal().source()).isEqualTo("APPRAISER");
    }

    @Test
    void inconsistentLegalDataIsAnErrorAndRequiresTheContract() {
        Map<CanonicalField, String> values = valid();
        values.put(COLLATERAL_TYPE, "PENHOR");
        assertThat(validate(values).hasCode("row.legal_fields_without_contract")).isTrue();
        assertThat(validate(values).validation().valid()).isPresent();

        values.put(CONTRACT_NUMBER, "C-1");
        values.put(CO_OBLIGOR_1_DOCUMENT, "529.982.247-25");
        values.put(CO_OBLIGOR_1_NAME, "Maria");
        values.put(CO_OBLIGOR_1_ROLE, "AVALISTA");
        values.put(APPRAISAL_DATE, "2030-01-01");
        RowValidation result = validator.validate(new SourceRow(2, values), null);
        assertThat(result.errors()).extracting(RowIssue::code).containsExactlyInAnyOrder("row.co_obligor_is_debtor",
                "row.collateral_other_incomplete", "row.required_missing", "row.appraisal_date_invalid");

        Map<CanonicalField, String> vehicle = valid();
        vehicle.put(CONTRACT_NUMBER, "C-1");
        vehicle.put(COLLATERAL_TYPE, "ALIENACAO_FIDUCIARIA_VEICULO");
        assertThat(validate(vehicle).hasCode("row.collateral_vehicle_identifier_required")).isTrue();
    }

    // ------------------------------------------------------------ formato numérico por coluna

    private static RowValidation validateWith(Map<CanonicalField, String> values, java.util.Set<CanonicalField> typed,
                                              br.com.gestorcobranca.ingestion.domain.model.format.FormatOption option) {
        var format = new br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat(Map.of(
                "valor_principal", new br.com.gestorcobranca.ingestion.domain.model.format.ColumnDecision(option,
                        br.com.gestorcobranca.ingestion.domain.model.format.DecisionSource.USER),
                "saldo", new br.com.gestorcobranca.ingestion.domain.model.format.ColumnDecision(option,
                        br.com.gestorcobranca.ingestion.domain.model.format.DecisionSource.USER)));
        return new RowValidator(LocalDate.of(2026, 10, 23), format).validate(new SourceRow(7, values, typed), null);
    }

    private static boolean has(RowValidation v, String code) {
        return v.issues().stream().map(RowIssue::code).anyMatch(code::equals);
    }

    @Test
    void amountsFollowTheColumnConventionAndOneDotZeroZeroNeverBecomesOneHundred() {
        var COMMA = br.com.gestorcobranca.ingestion.domain.model.format.FormatOption.COMMA;
        var DOT = br.com.gestorcobranca.ingestion.domain.model.format.FormatOption.DOT;
        Map<CanonicalField, String> values = valid();
        values.put(PRINCIPAL_AMOUNT, "1.00");
        assertThat(validateWith(values, java.util.Set.of(), DOT).valid().orElseThrow().principalAmount())
                .isEqualByComparingTo("1.00");
        // sob vírgula decimal, "1.00" não é lido (nunca vira 100)
        RowValidation comma = validateWith(values, java.util.Set.of(), COMMA);
        assertThat(comma.valid()).isEmpty();
        assertThat(has(comma, "row.principal_invalid")).isTrue();
        assertThat(comma.issues().getFirst().message()).contains("vírgula decimal");

        values.put(PRINCIPAL_AMOUNT, "1.000");
        assertThat(validateWith(values, java.util.Set.of(), COMMA).valid().orElseThrow().principalAmount())
                .isEqualByComparingTo("1000");
        assertThat(validateWith(values, java.util.Set.of(), DOT).valid().orElseThrow().principalAmount())
                .isEqualByComparingTo("1");
        // valor tipado (célula numérica / número JSON) dispensa a convenção
        values.put(PRINCIPAL_AMOUNT, "1000.5");
        assertThat(validateWith(values, java.util.Set.of(PRINCIPAL_AMOUNT), COMMA).valid().orElseThrow().principalAmount())
                .isEqualByComparingTo("1000.50");
    }

    @Test
    void reportsSpecificAmountProblems() {
        var COMMA = br.com.gestorcobranca.ingestion.domain.model.format.FormatOption.COMMA;
        Map<CanonicalField, String> values = valid();
        values.put(PRINCIPAL_AMOUNT, "1,234");
        assertThat(has(validateWith(values, java.util.Set.of(), COMMA), "row.amount_too_many_decimals")).isTrue();
        values.put(PRINCIPAL_AMOUNT, "1,23457E+11");
        assertThat(has(validateWith(values, java.util.Set.of(), COMMA), "row.amount_scientific_notation")).isTrue();
        values.put(PRINCIPAL_AMOUNT, "10.005");
        assertThat(has(validateWith(values, java.util.Set.of(PRINCIPAL_AMOUNT), COMMA), "row.amount_too_many_decimals")).isTrue();
        // saldo com problema é aviso: a linha continua válida
        values.put(PRINCIPAL_AMOUNT, "10,00");
        values.put(REPORTED_BALANCE, "1,23E+3");
        values.put(BALANCE_REFERENCE_DATE, "01/10/2026");
        RowValidation v = validateWith(values, java.util.Set.of(), COMMA);
        assertThat(v.valid()).isPresent();
        assertThat(v.warnings()).anySatisfy(w -> assertThat(w.message()).contains("notação científica"));
    }

    @Test
    void identifiersConvertedByTheSpreadsheetAreErrors() {
        Map<CanonicalField, String> values = valid();
        values.put(TITLE_EXTERNAL_ID, "1,23457E+15");
        assertThat(validate(values).hasCode("row.scientific_notation")).isTrue();
        values.put(TITLE_EXTERNAL_ID, "2E3456");
        assertThat(validate(values).validation().valid()).isPresent();
        values.put(TITLE_EXTERNAL_ID, "1234567890123460000");
        RowValidation typed = new RowValidator(LocalDate.of(2026, 10, 23))
                .validate(new SourceRow(7, values, java.util.Set.of(TITLE_EXTERNAL_ID)), null);
        assertThat(has(typed, "row.identifier_precision_lost")).isTrue();
        assertThat(validate(values).validation().valid()).isPresent();
        values.put(TITLE_EXTERNAL_ID, "T-1");
        values.put(DEBTOR_DOCUMENT, "5,29982E+10");
        RowValidation doc = validate(values).validation();
        assertThat(has(doc, "row.scientific_notation")).isTrue();
        assertThat(has(doc, "row.document_invalid")).isFalse();
    }
}
