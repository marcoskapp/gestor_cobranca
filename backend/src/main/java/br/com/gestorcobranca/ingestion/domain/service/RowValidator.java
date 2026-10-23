package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow.ContactKind;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow.ParsedContact;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow.ParsedContract;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static br.com.gestorcobranca.ingestion.domain.model.CanonicalField.*;

/**
 * Validação e conversão de uma linha canônica (regras em {@code documentação/layout-importacao.md}).
 * Função pura: não acessa banco nem relógio.
 * <p>
 * Critério de severidade: problemas no que identifica o devedor e o título ou nos valores do
 * título e do contrato são <b>erros</b> (a linha não é gravada); problemas em dados
 * complementares (nascimento, contatos, endereço, saldo informado) são <b>avisos</b> e o dado
 * problemático é ignorado — a dívida é carregada e o credor corrige o dado na próxima carga.
 */
public final class RowValidator {

    static final int NAME_MAX = 200;
    static final int EXTERNAL_ID_MAX = 80;
    static final int PRODUCT_MAX = 120;
    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    private static final Set<String> STATES = Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT",
            "MS", "MG", "PA", "PB", "PR", "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO");
    private static final CanonicalField[][] PHONES = {{PHONE_1, WHATSAPP_1}, {PHONE_2, WHATSAPP_2}, {PHONE_3, WHATSAPP_3}};

    private final LocalDate today;

    /** @param today data de hoje no fuso de operação (nascimento no futuro é aviso) */
    public RowValidator(LocalDate today) {
        this.today = today;
    }

    /** Identificador externo do título da linha (chave de duplicidade), ou nulo se ausente/inválido. */
    public static String titleKey(SourceRow row) {
        return row.get(TITLE_EXTERNAL_ID).filter(id -> id.length() <= EXTERNAL_ID_MAX).orElse(null);
    }

    /**
     * @param duplicateOfRow linha anterior do mesmo arquivo com o mesmo identificador externo de título (nulo se não houver)
     */
    public RowValidation validate(SourceRow source, Long duplicateOfRow) {
        Context ctx = new Context(source);

        Boolean company = personType(ctx);
        Document document = document(ctx, company);
        String name = name(ctx);
        LocalDate birthDate = birthDate(ctx);
        List<ParsedContact> contacts = new ArrayList<>();
        phones(ctx, contacts);
        for (CanonicalField email : List.of(EMAIL_1, EMAIL_2)) {
            source.get(email).ifPresent(v -> contacts.add(new ParsedContact(ContactKind.EMAIL, v, false)));
        }
        address(ctx).ifPresent(a -> contacts.add(new ParsedContact(ContactKind.ADDRESS, a, false)));
        ParsedContract contract = contract(ctx);

        String externalId = ctx.required(TITLE_EXTERNAL_ID);
        if (externalId != null && externalId.length() > EXTERNAL_ID_MAX) {
            ctx.error(TITLE_EXTERNAL_ID, "row.external_id_too_long",
                    "Identificador do título deve ter até %d caracteres".formatted(EXTERNAL_ID_MAX));
        } else if (externalId != null && duplicateOfRow != null) {
            ctx.error(TITLE_EXTERNAL_ID, "row.duplicate_title",
                    "Título repetido no arquivo (primeira ocorrência na linha %d)".formatted(duplicateOfRow));
        }
        Integer installment = source.get(INSTALLMENT_NUMBER).map(raw -> {
            Optional<Integer> parsed = BrazilianFormats.parseInteger(raw).filter(n -> n > 0);
            if (parsed.isEmpty()) {
                ctx.error(INSTALLMENT_NUMBER, "row.installment_invalid", "Número da parcela deve ser inteiro positivo");
            }
            return parsed.orElse(null);
        }).orElse(null);
        LocalDate dueDate = ctx.requiredDate(DUE_DATE, "row.due_date_invalid", "Vencimento inválido: use dd/mm/aaaa ou aaaa-mm-dd");
        BigDecimal principal = principal(ctx);
        ReportedBalance balance = reportedBalance(ctx);

        if (ctx.hasErrors()) {
            return new RowValidation(source.rowNumber(), null, ctx.issues);
        }
        ParsedRow row = new ParsedRow(source.rowNumber(), document, name, birthDate, contacts, contract, externalId,
                installment, dueDate, principal, balance == null ? null : balance.amount(),
                balance == null ? null : balance.referenceDate());
        return new RowValidation(source.rowNumber(), row, ctx.issues);
    }

    private static Boolean personType(Context ctx) {
        return ctx.source.get(PERSON_TYPE).map(raw -> {
            String value = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                    .toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            return switch (value) {
                case "F", "PF", "FISICA", "PESSOA FISICA", "INDIVIDUAL" -> Boolean.FALSE;
                case "J", "PJ", "JURIDICA", "PESSOA JURIDICA", "COMPANY" -> Boolean.TRUE;
                default -> {
                    ctx.error(PERSON_TYPE, "row.person_type_invalid", "Tipo de pessoa deve ser F (física) ou J (jurídica)");
                    yield null;
                }
            };
        }).orElse(null);
    }

    private static Document document(Context ctx, Boolean company) {
        String raw = ctx.required(DEBTOR_DOCUMENT);
        if (raw == null) {
            return null;
        }
        try {
            DocumentNormalizer.Result result = DocumentNormalizer.normalize(raw, company);
            if (company != null && company != (result.document().type() == Document.DocumentType.CNPJ)) {
                ctx.error(PERSON_TYPE, "row.person_type_mismatch", "Tipo de pessoa não corresponde ao documento (CPF/CNPJ)");
            }
            if (result.padded()) {
                ctx.warning(DEBTOR_DOCUMENT, "row.document_padded",
                        "Documento completado com zeros à esquerda (verifique a formatação da coluna)");
            }
            return result.document();
        } catch (BusinessRuleException e) {
            ctx.error(DEBTOR_DOCUMENT, "row.document_invalid", "CPF/CNPJ inválido");
            return null;
        }
    }

    private static String name(Context ctx) {
        String raw = ctx.required(DEBTOR_NAME);
        if (raw == null) {
            return null;
        }
        String name = raw.replaceAll("\\s+", " ");
        if (name.length() > NAME_MAX) {
            ctx.error(DEBTOR_NAME, "row.name_too_long", "Nome deve ter até %d caracteres".formatted(NAME_MAX));
        }
        return name;
    }

    private LocalDate birthDate(Context ctx) {
        return ctx.source.get(BIRTH_DATE).flatMap(raw -> {
            Optional<LocalDate> date = BrazilianFormats.parseDate(raw)
                    .filter(d -> d.isAfter(MIN_BIRTH_DATE) && !d.isAfter(today));
            if (date.isEmpty()) {
                ctx.warning(BIRTH_DATE, "row.birth_date_invalid", "Data de nascimento inválida: dado ignorado");
            }
            return date;
        }).orElse(null);
    }

    private static void phones(Context ctx, List<ParsedContact> contacts) {
        for (CanonicalField[] pair : PHONES) {
            Optional<String> phone = ctx.source.get(pair[0]);
            boolean whatsapp = ctx.source.get(pair[1]).map(raw -> {
                Optional<Boolean> flag = BrazilianFormats.parseFlag(raw);
                if (flag.isEmpty()) {
                    ctx.warning(pair[1], "row.whatsapp_invalid", "Indicação de WhatsApp deve ser S ou N: considerado N");
                }
                return flag.orElse(false);
            }).orElse(false);
            phone.ifPresent(v -> contacts.add(new ParsedContact(ContactKind.PHONE, v, whatsapp)));
        }
    }

    /** Endereço em uma linha: "logradouro, número - complemento - bairro - cidade/UF - CEP 00000-000". */
    private static Optional<String> address(Context ctx) {
        SourceRow s = ctx.source;
        Optional<String> street = s.get(ADDRESS_STREET);
        boolean anyPart = s.get(ADDRESS_NUMBER).isPresent() || s.get(ADDRESS_COMPLEMENT).isPresent()
                || s.get(ADDRESS_DISTRICT).isPresent() || s.get(ADDRESS_CITY).isPresent()
                || s.get(ADDRESS_STATE).isPresent() || s.get(ADDRESS_ZIP).isPresent();
        if (street.isEmpty()) {
            if (anyPart) {
                ctx.warning(ADDRESS_STREET, "row.address_incomplete", "Endereço sem logradouro: endereço ignorado");
            }
            return Optional.empty();
        }
        StringBuilder address = new StringBuilder(street.get());
        s.get(ADDRESS_NUMBER).ifPresent(n -> address.append(", ").append(n));
        s.get(ADDRESS_COMPLEMENT).ifPresent(c -> address.append(" - ").append(c));
        s.get(ADDRESS_DISTRICT).ifPresent(d -> address.append(" - ").append(d));
        Optional<String> state = s.get(ADDRESS_STATE).map(uf -> uf.toUpperCase(Locale.ROOT)).filter(uf -> {
            boolean valid = STATES.contains(uf);
            if (!valid) {
                ctx.warning(ADDRESS_STATE, "row.state_invalid", "UF inválida: dado ignorado", uf);
            }
            return valid;
        });
        Optional<String> city = s.get(ADDRESS_CITY);
        if (city.isPresent() || state.isPresent()) {
            address.append(" - ").append(city.orElse("")).append(state.map(uf -> (city.isPresent() ? "/" : "") + uf).orElse(""));
        }
        s.get(ADDRESS_ZIP).ifPresent(raw -> {
            String digits = raw.replaceAll("\\D", "");
            if (digits.length() == 8) {
                address.append(" - CEP ").append(digits, 0, 5).append('-').append(digits.substring(5));
            } else {
                ctx.warning(ADDRESS_ZIP, "row.zip_invalid", "CEP deve ter 8 dígitos: dado ignorado");
            }
        });
        return Optional.of(address.toString());
    }

    private static ParsedContract contract(Context ctx) {
        SourceRow s = ctx.source;
        Optional<String> number = s.get(CONTRACT_NUMBER);
        if (number.isEmpty()) {
            if (s.get(PRODUCT).isPresent() || s.get(CONTRACT_DATE).isPresent() || s.get(CONTRACT_AMOUNT).isPresent()) {
                ctx.warning(CONTRACT_NUMBER, "row.contract_number_missing",
                        "Dados de contrato sem número do contrato: dados do contrato ignorados");
            }
            return null;
        }
        if (number.get().length() > EXTERNAL_ID_MAX) {
            ctx.error(CONTRACT_NUMBER, "row.contract_number_too_long",
                    "Número do contrato deve ter até %d caracteres".formatted(EXTERNAL_ID_MAX));
        }
        String product = s.get(PRODUCT).orElse(null);
        if (product != null && product.length() > PRODUCT_MAX) {
            ctx.error(PRODUCT, "row.product_too_long", "Produto deve ter até %d caracteres".formatted(PRODUCT_MAX));
        }
        LocalDate signedAt = s.get(CONTRACT_DATE).map(raw -> BrazilianFormats.parseDate(raw).orElseGet(() -> {
            ctx.error(CONTRACT_DATE, "row.contract_date_invalid", "Data do contrato inválida: use dd/mm/aaaa ou aaaa-mm-dd");
            return null;
        })).orElse(null);
        BigDecimal amount = s.get(CONTRACT_AMOUNT).map(raw -> {
            Optional<BigDecimal> parsed = BrazilianFormats.parseAmount(raw);
            if (parsed.isEmpty()) {
                ctx.error(CONTRACT_AMOUNT, "row.contract_amount_invalid", "Valor do contrato inválido: use 1.234,56 ou 1234.56");
                return null;
            }
            if (parsed.get().signum() < 0) {
                ctx.error(CONTRACT_AMOUNT, "row.contract_amount_negative", "Valor do contrato não pode ser negativo");
                return null;
            }
            return parsed.get();
        }).orElse(null);
        return new ParsedContract(number.get(), product, signedAt, amount);
    }

    private static BigDecimal principal(Context ctx) {
        String raw = ctx.required(PRINCIPAL_AMOUNT);
        if (raw == null) {
            return null;
        }
        Optional<BigDecimal> parsed = BrazilianFormats.parseAmount(raw);
        if (parsed.isEmpty()) {
            ctx.error(PRINCIPAL_AMOUNT, "row.principal_invalid", "Valor principal inválido: use 1.234,56 ou 1234.56");
            return null;
        }
        if (parsed.get().signum() < 0) {
            ctx.error(PRINCIPAL_AMOUNT, "row.principal_negative", "Valor principal não pode ser negativo");
            return null;
        }
        if (parsed.get().signum() == 0) {
            ctx.error(PRINCIPAL_AMOUNT, "row.principal_zero", "Valor principal deve ser maior que zero");
            return null;
        }
        return parsed.get();
    }

    /** Saldo informado e data-base: dado de conciliação; qualquer problema é aviso e o saldo é ignorado. */
    private static ReportedBalance reportedBalance(Context ctx) {
        SourceRow s = ctx.source;
        Optional<String> rawBalance = s.get(REPORTED_BALANCE);
        Optional<String> rawDate = s.get(BALANCE_REFERENCE_DATE);
        if (rawBalance.isEmpty()) {
            if (rawDate.isPresent()) {
                ctx.warning(BALANCE_REFERENCE_DATE, "row.balance_date_without_balance", "Data do saldo sem saldo: dado ignorado");
            }
            return null;
        }
        Optional<BigDecimal> balance = BrazilianFormats.parseAmount(rawBalance.get());
        if (balance.isEmpty()) {
            ctx.warning(REPORTED_BALANCE, "row.balance_invalid", "Saldo inválido: saldo ignorado");
            return null;
        }
        if (balance.get().signum() < 0) {
            ctx.warning(REPORTED_BALANCE, "row.balance_negative", "Saldo negativo: saldo ignorado");
            return null;
        }
        if (rawDate.isEmpty()) {
            ctx.warning(BALANCE_REFERENCE_DATE, "row.balance_date_required", "Saldo sem data de referência: saldo ignorado");
            return null;
        }
        Optional<LocalDate> date = BrazilianFormats.parseDate(rawDate.get());
        if (date.isEmpty()) {
            ctx.warning(BALANCE_REFERENCE_DATE, "row.balance_date_invalid", "Data do saldo inválida: saldo ignorado");
            return null;
        }
        return new ReportedBalance(balance.get(), date.get());
    }

    private record ReportedBalance(BigDecimal amount, LocalDate referenceDate) {
    }

    /** Acumula ocorrências da linha. */
    private static final class Context {

        private final SourceRow source;
        private final List<RowIssue> issues = new ArrayList<>();

        private Context(SourceRow source) {
            this.source = source;
        }

        String required(CanonicalField field) {
            Optional<String> value = source.get(field);
            if (value.isEmpty()) {
                issues.add(RowIssue.error(source.rowNumber(), field, "row.required_missing",
                        "Campo obrigatório não informado: " + field.column(), null));
            }
            return value.orElse(null);
        }

        LocalDate requiredDate(CanonicalField field, String code, String message) {
            String raw = required(field);
            if (raw == null) {
                return null;
            }
            Optional<LocalDate> date = BrazilianFormats.parseDate(raw);
            if (date.isEmpty()) {
                error(field, code, message);
            }
            return date.orElse(null);
        }

        void error(CanonicalField field, String code, String message) {
            issues.add(RowIssue.error(source.rowNumber(), field, code, message, source.get(field).orElse(null)));
        }

        void warning(CanonicalField field, String code, String message) {
            warning(field, code, message, source.get(field).orElse(null));
        }

        void warning(CanonicalField field, String code, String message, String value) {
            issues.add(RowIssue.warning(source.rowNumber(), field, code, message, value));
        }

        boolean hasErrors() {
            return issues.stream().anyMatch(RowIssue::isError);
        }
    }
}
