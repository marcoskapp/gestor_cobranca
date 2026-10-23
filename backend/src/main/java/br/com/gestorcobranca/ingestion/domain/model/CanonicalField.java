package br.com.gestorcobranca.ingestion.domain.model;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Campos canônicos de uma linha de carga. Todo layout de credor mapeia suas colunas para estes
 * campos; o layout padrão usa o próprio {@link #column()} como cabeçalho. A especificação completa
 * (formatos aceitos, obrigatoriedade, exemplo) está em {@code documentação/layout-importacao.md}.
 * <p>
 * {@code personal} indica dado pessoal do devedor: em relatórios de erro o valor é sempre
 * mascarado integralmente.
 */
public enum CanonicalField {

    DEBTOR_DOCUMENT("documento", true, true),
    DEBTOR_NAME("nome", true, true),
    PERSON_TYPE("tipo_pessoa", false, false),
    BIRTH_DATE("data_nascimento", false, true),
    PHONE_1("telefone_1", false, true),
    WHATSAPP_1("whatsapp_1", false, false),
    PHONE_2("telefone_2", false, true),
    WHATSAPP_2("whatsapp_2", false, false),
    PHONE_3("telefone_3", false, true),
    WHATSAPP_3("whatsapp_3", false, false),
    EMAIL_1("email_1", false, true),
    EMAIL_2("email_2", false, true),
    ADDRESS_STREET("endereco_logradouro", false, true),
    ADDRESS_NUMBER("endereco_numero", false, true),
    ADDRESS_COMPLEMENT("endereco_complemento", false, true),
    ADDRESS_DISTRICT("endereco_bairro", false, true),
    ADDRESS_CITY("endereco_cidade", false, false),
    ADDRESS_STATE("endereco_uf", false, false),
    ADDRESS_ZIP("endereco_cep", false, true),
    CONTRACT_NUMBER("contrato", false, false),
    PRODUCT("produto", false, false),
    CONTRACT_DATE("data_contrato", false, false),
    CONTRACT_AMOUNT("valor_contrato", false, false),
    TITLE_EXTERNAL_ID("id_titulo", true, false),
    INSTALLMENT_NUMBER("parcela", false, false),
    DUE_DATE("vencimento", true, false),
    PRINCIPAL_AMOUNT("valor_principal", true, false),
    REPORTED_BALANCE("saldo", false, false),
    BALANCE_REFERENCE_DATE("data_saldo", false, false);

    private static final Map<String, CanonicalField> BY_COLUMN = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CanonicalField::column, Function.identity()));

    private final String column;
    private final boolean required;
    private final boolean personal;

    CanonicalField(String column, boolean required, boolean personal) {
        this.column = column;
        this.required = required;
        this.personal = personal;
    }

    /** Nome da coluna no layout padrão e da chave nos registros da API. */
    public String column() {
        return column;
    }

    public boolean required() {
        return required;
    }

    public boolean personal() {
        return personal;
    }

    public static Optional<CanonicalField> byColumn(String column) {
        return column == null ? Optional.empty() : Optional.ofNullable(BY_COLUMN.get(normalizeHeader(column)));
    }

    /**
     * Normaliza um cabeçalho para comparação: minúsculas, sem acentos, sem espaços nas pontas e
     * com espaços internos reduzidos a um. "Data de Nascimento " e "data de nascimento" casam.
     */
    public static String normalizeHeader(String header) {
        String stripped = Normalizer.normalize(header.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
