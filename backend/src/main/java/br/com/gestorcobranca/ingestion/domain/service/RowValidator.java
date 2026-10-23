package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow.ContactKind;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow.ParsedContact;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow.ParsedContract;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.LegalTerms;
import br.com.gestorcobranca.ingestion.domain.model.ParsedLegal;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.model.TitleSituation;
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
import java.util.Arrays;
import java.util.stream.Stream;

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
    private static final List<CanonicalField> FORMULA_GUARDED = List.of(DEBTOR_NAME, TITLE_EXTERNAL_ID, CONTRACT_NUMBER,
            PRODUCT, ADDRESS_STREET, ADDRESS_NUMBER, ADDRESS_COMPLEMENT, ADDRESS_DISTRICT, ADDRESS_CITY,
            CO_OBLIGOR_1_NAME, CO_OBLIGOR_2_NAME, COLLATERAL_EXTERNAL_ID, COLLATERAL_DESCRIPTION,
            COLLATERAL_REGISTRY_OFFICE, COLLATERAL_MUNICIPALITY, COLLATERAL_LIEN_AUTHORITY, COLLATERAL_LIEN_NUMBER);
    private static final List<CanonicalField> NATURE_FIELDS = List.of(INSTRUMENT_TYPE, EXECUTIVE_TITLE);
    private static final CanonicalField[][] CO_OBLIGORS = {
            {CO_OBLIGOR_1_DOCUMENT, CO_OBLIGOR_1_NAME, CO_OBLIGOR_1_ROLE, CO_OBLIGOR_1_SINCE},
            {CO_OBLIGOR_2_DOCUMENT, CO_OBLIGOR_2_NAME, CO_OBLIGOR_2_ROLE, CO_OBLIGOR_2_SINCE}};
    private static final List<CanonicalField> COLLATERAL_FIELDS = List.of(COLLATERAL_EXTERNAL_ID, COLLATERAL_TYPE,
            COLLATERAL_ASSET_KIND, COLLATERAL_DESCRIPTION, COLLATERAL_PLATE, COLLATERAL_RENAVAM, COLLATERAL_CHASSIS,
            COLLATERAL_MANUFACTURE_YEAR, COLLATERAL_MODEL_YEAR, COLLATERAL_REGISTRY_NUMBER, COLLATERAL_REGISTRY_OFFICE,
            COLLATERAL_MUNICIPALITY, COLLATERAL_STATE, COLLATERAL_LIEN_AUTHORITY, COLLATERAL_LIEN_NUMBER,
            COLLATERAL_LIEN_DATE);
    private static final List<CanonicalField> APPRAISAL_FIELDS = List.of(APPRAISAL_AMOUNT, APPRAISAL_DATE, APPRAISAL_SOURCE);
    static final int LEGAL_TEXT_MAX = 150;
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
        TitleSituation situation = situation(ctx);
        rejectFormulas(ctx);
        ParsedLegal legal = legal(ctx, contract, document);

        if (ctx.hasErrors()) {
            return new RowValidation(source.rowNumber(), null, ctx.issues);
        }
        ParsedRow row = new ParsedRow(source.rowNumber(), document, name, birthDate, contacts, contract, externalId,
                installment, dueDate, principal, balance == null ? null : balance.amount(),
                balance == null ? null : balance.referenceDate(), situation, legal);
        return new RowValidation(source.rowNumber(), row, ctx.issues);
    }

    // ------------------------------------------------------------ dados jurídicos (A.4)

    /**
     * Natureza do título, coobrigados e garantia. Exigem o contrato da linha (sem ele: aviso e os
     * dados são ignorados, como os demais dados de contrato). Dado jurídico inconsistente é
     * <b>erro</b>: a linha é gravada de forma atômica com o contrato, e carregar o título sem a
     * garantia ou o coobrigado informado levaria a decisões de cobrança e jurídicas erradas.
     */
    private ParsedLegal legal(Context ctx, ParsedContract contract, Document debtorDocument) {
        SourceRow s = ctx.source;
        boolean anyNature = NATURE_FIELDS.stream().anyMatch(f -> s.get(f).isPresent());
        boolean anyParty = Arrays.stream(CO_OBLIGORS).flatMap(Arrays::stream).anyMatch(f -> s.get(f).isPresent());
        boolean anyCollateral = COLLATERAL_FIELDS.stream().anyMatch(f -> s.get(f).isPresent());
        boolean anyAppraisal = APPRAISAL_FIELDS.stream().anyMatch(f -> s.get(f).isPresent());
        if (!anyNature && !anyParty && !anyCollateral && !anyAppraisal) {
            return null;
        }
        if (contract == null) {
            ctx.warning(CONTRACT_NUMBER, "row.legal_fields_without_contract",
                    "Natureza, coobrigados e garantias exigem o número do contrato: dados ignorados");
            return null;
        }
        ParsedLegal.Nature nature = anyNature ? nature(ctx) : null;
        List<ParsedLegal.Party> parties = new ArrayList<>();
        for (CanonicalField[] fields : CO_OBLIGORS) {
            party(ctx, fields, debtorDocument).ifPresent(parties::add);
        }
        ParsedLegal.Collateral collateral = anyCollateral ? collateral(ctx) : null;
        if (anyAppraisal && collateral == null && !anyCollateral) {
            ctx.warning(APPRAISAL_AMOUNT, "row.appraisal_without_collateral", "Avaliação sem garantia: dados ignorados");
        }
        return new ParsedLegal(nature, parties, collateral);
    }

    private static ParsedLegal.Nature nature(Context ctx) {
        Optional<String> rawType = ctx.source.get(INSTRUMENT_TYPE);
        Boolean executive = ctx.source.get(EXECUTIVE_TITLE).map(raw -> BrazilianFormats.parseFlag(raw).orElseGet(() -> {
            ctx.error(EXECUTIVE_TITLE, "row.executive_title_invalid", "Título executivo deve ser S ou N");
            return null;
        })).orElse(null);
        if (rawType.isEmpty()) {
            ctx.warning(EXECUTIVE_TITLE, "row.instrument_type_missing",
                    "Título executivo informado sem a natureza do título: dado ignorado");
            return null;
        }
        Optional<String> type = LegalTerms.parse(LegalTerms.INSTRUMENT_TYPES, rawType.get());
        if (type.isEmpty()) {
            ctx.error(INSTRUMENT_TYPE, "row.instrument_type_invalid",
                    "Natureza deve ser CONTRATO, CCB, NOTA_PROMISSORIA, DUPLICATA, CHEQUE, CONFISSAO_DIVIDA ou OUTRO");
            return null;
        }
        return new ParsedLegal.Nature(type.get(), Boolean.TRUE.equals(executive));
    }

    private Optional<ParsedLegal.Party> party(Context ctx, CanonicalField[] f, Document debtorDocument) {
        SourceRow s = ctx.source;
        if (Arrays.stream(f).noneMatch(field -> s.get(field).isPresent())) {
            return Optional.empty();
        }
        String rawDocument = ctx.required(f[0]);
        String name = ctx.required(f[1]);
        String rawRole = ctx.required(f[2]);
        Document document = null;
        if (rawDocument != null) {
            try {
                document = DocumentNormalizer.normalize(rawDocument, null).document();
                if (document.equals(debtorDocument)) {
                    ctx.error(f[0], "row.co_obligor_is_debtor", "Coobrigado não pode ser o próprio devedor do título");
                }
            } catch (BusinessRuleException e) {
                ctx.error(f[0], "row.co_obligor_document_invalid", "CPF/CNPJ do coobrigado inválido");
            }
        }
        if (name != null) {
            name = name.replaceAll("\\s+", " ");
            if (name.length() > NAME_MAX) {
                ctx.error(f[1], "row.co_obligor_name_too_long", "Nome do coobrigado deve ter até %d caracteres".formatted(NAME_MAX));
            }
        }
        String role = rawRole == null ? null : LegalTerms.parse(LegalTerms.ROLES, rawRole).orElseGet(() -> {
            ctx.error(f[2], "row.co_obligor_role_invalid",
                    "Papel do coobrigado deve ser AVALISTA, FIADOR, CODEVEDOR ou GARANTIDOR");
            return null;
        });
        LocalDate since = s.get(f[3]).map(raw -> BrazilianFormats.parseDate(raw).filter(d -> !d.isAfter(today))
                .orElseGet(() -> {
                    ctx.error(f[3], "row.co_obligor_since_invalid", "Data de início do coobrigado inválida ou futura");
                    return null;
                })).orElse(null);
        return document == null || name == null || role == null ? Optional.empty()
                : Optional.of(new ParsedLegal.Party(document, name, role, since));
    }

    private ParsedLegal.Collateral collateral(Context ctx) {
        SourceRow s = ctx.source;
        String rawType = ctx.required(COLLATERAL_TYPE);
        String type = rawType == null ? null : LegalTerms.parse(LegalTerms.COLLATERAL_TYPES, rawType).orElseGet(() -> {
            ctx.error(COLLATERAL_TYPE, "row.collateral_type_invalid", "Tipo de garantia inválido (ver layout)");
            return null;
        });
        String kind = s.get(COLLATERAL_ASSET_KIND).map(raw -> LegalTerms.parse(LegalTerms.ASSET_KINDS, raw).orElseGet(() -> {
            ctx.error(COLLATERAL_ASSET_KIND, "row.collateral_asset_kind_invalid", "Bem deve ser VEICULO, IMOVEL ou OUTRO");
            return null;
        })).orElse(type == null ? null : LegalTerms.defaultAssetKind(type));
        String externalId = text(ctx, COLLATERAL_EXTERNAL_ID, EXTERNAL_ID_MAX);
        String description = text(ctx, COLLATERAL_DESCRIPTION, 500);
        Integer manufactureYear = year(ctx, COLLATERAL_MANUFACTURE_YEAR);
        Integer modelYear = year(ctx, COLLATERAL_MODEL_YEAR);
        String state = s.get(COLLATERAL_STATE).map(uf -> uf.toUpperCase(Locale.ROOT)).orElse(null);
        if (state != null && !STATES.contains(state)) {
            ctx.error(COLLATERAL_STATE, "row.collateral_state_invalid", "UF do imóvel inválida");
        }
        if ("VEHICLE".equals(kind) && Stream.of(COLLATERAL_PLATE, COLLATERAL_RENAVAM, COLLATERAL_CHASSIS)
                .noneMatch(f -> s.get(f).isPresent())) {
            ctx.error(COLLATERAL_PLATE, "row.collateral_vehicle_identifier_required",
                    "Veículo em garantia: informe placa, RENAVAM ou chassi");
        }
        if ("REAL_ESTATE".equals(kind)) {
            for (CanonicalField f : List.of(COLLATERAL_REGISTRY_NUMBER, COLLATERAL_REGISTRY_OFFICE,
                    COLLATERAL_MUNICIPALITY, COLLATERAL_STATE)) {
                if (s.get(f).isEmpty()) {
                    ctx.error(f, "row.collateral_real_estate_incomplete",
                            "Imóvel em garantia: informe matrícula, cartório, município e UF");
                }
            }
        }
        if ("OTHER".equals(kind) && (externalId == null || description == null)) {
            ctx.error(externalId == null ? COLLATERAL_EXTERNAL_ID : COLLATERAL_DESCRIPTION,
                    "row.collateral_other_incomplete", "Garantia sem bem identificado: informe garantia_id e descrição");
        }
        LocalDate lienDate = s.get(COLLATERAL_LIEN_DATE).map(raw -> BrazilianFormats.parseDate(raw).orElseGet(() -> {
            ctx.error(COLLATERAL_LIEN_DATE, "row.collateral_lien_date_invalid", "Data do gravame inválida");
            return null;
        })).orElse(null);
        ParsedLegal.Appraisal appraisal = appraisal(ctx);
        if (type == null || kind == null) {
            return null;
        }
        return new ParsedLegal.Collateral(externalId, type, kind, description, s.get(COLLATERAL_PLATE).orElse(null),
                s.get(COLLATERAL_RENAVAM).orElse(null), s.get(COLLATERAL_CHASSIS).orElse(null), manufactureYear, modelYear,
                s.get(COLLATERAL_REGISTRY_NUMBER).orElse(null), text(ctx, COLLATERAL_REGISTRY_OFFICE, LEGAL_TEXT_MAX),
                text(ctx, COLLATERAL_MUNICIPALITY, 100), state, text(ctx, COLLATERAL_LIEN_AUTHORITY, LEGAL_TEXT_MAX),
                text(ctx, COLLATERAL_LIEN_NUMBER, EXTERNAL_ID_MAX), lienDate, appraisal);
    }

    private ParsedLegal.Appraisal appraisal(Context ctx) {
        SourceRow s = ctx.source;
        if (APPRAISAL_FIELDS.stream().noneMatch(f -> s.get(f).isPresent())) {
            return null;
        }
        String raw = ctx.required(APPRAISAL_AMOUNT);
        BigDecimal amount = raw == null ? null : BrazilianFormats.parseAmount(raw).filter(v -> v.signum() > 0)
                .orElseGet(() -> {
                    ctx.error(APPRAISAL_AMOUNT, "row.appraisal_amount_invalid", "Valor da avaliação deve ser maior que zero");
                    return null;
                });
        LocalDate date = s.get(APPRAISAL_DATE).map(d -> BrazilianFormats.parseDate(d).filter(v -> !v.isAfter(today))
                .orElseGet(() -> {
                    ctx.error(APPRAISAL_DATE, "row.appraisal_date_invalid", "Data da avaliação inválida ou futura");
                    return null;
                })).orElse(null);
        String source = s.get(APPRAISAL_SOURCE).map(v -> LegalTerms.parse(LegalTerms.APPRAISAL_SOURCES, v).orElseGet(() -> {
            ctx.error(APPRAISAL_SOURCE, "row.appraisal_source_invalid",
                    "Fonte da avaliação deve ser CREDOR, AVALIADOR, FIPE, MERCADO ou LEILAO");
            return null;
        })).orElse(null);
        return amount == null ? null : new ParsedLegal.Appraisal(amount, date, source);
    }

    private static Integer year(Context ctx, CanonicalField field) {
        return ctx.source.get(field).map(raw -> BrazilianFormats.parseInteger(raw).filter(y -> y >= 1900 && y <= 2199)
                .orElseGet(() -> {
                    ctx.error(field, "row.collateral_year_invalid", "Ano inválido");
                    return null;
                })).orElse(null);
    }

    private static String text(Context ctx, CanonicalField field, int max) {
        String value = ctx.source.get(field).orElse(null);
        if (value != null && value.length() > max) {
            ctx.error(field, "row.text_too_long", "%s deve ter até %d caracteres".formatted(field.column(), max));
        }
        return value;
    }

    /** Situação informada pelo credor: valor desconhecido é erro, pois mudaria o tratamento do título. */
    private static TitleSituation situation(Context ctx) {
        return ctx.source.get(TITLE_SITUATION).map(raw -> TitleSituation.parse(raw).orElseGet(() -> {
            ctx.error(TITLE_SITUATION, "row.situation_invalid",
                    "Situação deve ser ATIVO, PAGO, SUSPENSO, CONTESTADO ou CANCELADO");
            return null;
        })).orElse(TitleSituation.ACTIVE);
    }

    /**
     * Conteúdo com aparência de fórmula de planilha ({@code =}, {@code @}) em campos de texto livre
     * gravados na base: recusado nos identificadores e no nome (erro), para que o valor nunca seja
     * reexportado como fórmula (CSV/XLSX injection). Valores numéricos, datas e telefones
     * ({@code +55…}) não são afetados.
     */
    private static void rejectFormulas(Context ctx) {
        for (CanonicalField field : FORMULA_GUARDED) {
            ctx.source.get(field).filter(RowValidator::looksLikeFormula).ifPresent(v ->
                    ctx.error(field, "row.formula_not_allowed", "Conteúdo com formato de fórmula não é aceito"));
        }
    }

    static boolean looksLikeFormula(String value) {
        return !value.isEmpty() && (value.charAt(0) == '=' || value.charAt(0) == '@');
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
