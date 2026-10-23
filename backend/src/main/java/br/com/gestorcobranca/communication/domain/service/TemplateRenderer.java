package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.TemplateVariable;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.net.URI;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Definição e renderização de templates com variáveis tipadas ({@code {{nome}}}).
 * <ul>
 *   <li>Definição: toda variável usada no assunto/corpo é declarada e toda declarada é usada;
 *       nomes únicos; no máximo {@value #MAX_VARIABLES}.</li>
 *   <li>Renderização: valores validados pelo tipo; valores livres (TEXT, CODE, URL) não podem
 *       conter documento, e-mail ou telefone (minimização de dados pessoais); sem quebras de linha.</li>
 *   <li>Parâmetros posicionais (WhatsApp): a ordem é a da lista de variáveis declaradas.</li>
 * </ul>
 */
public final class TemplateRenderer {

    public static final int MAX_VARIABLES = 20;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_]{0,39})\\s*}}");
    private static final Pattern FIRST_NAME = Pattern.compile("\\p{L}[\\p{L}'-]{0,29}");
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,9}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9 .:/_-]+");
    /** CPF/CNPJ formatados. */
    private static final Pattern FORMATTED_DOCUMENT = Pattern.compile(
            "\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}|\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}");
    /** Sequências isoladas de 11 ou 14 dígitos (CPF/CNPJ sem máscara). */
    private static final Pattern BARE_DOCUMENT = Pattern.compile("(?<!\\d)(\\d{11}|\\d{14})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    /** Telefones: (11) 98765-4321, 11 98765-4321, +55 11 987654321. */
    private static final Pattern PHONE = Pattern.compile("(?<![\\d+])(\\+?55\\s?)?\\(?\\d{2}\\)?\\s?9?\\d{4}[-\\s]?\\d{4}(?!\\d)");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private TemplateRenderer() {
    }

    /** Resultado da renderização: textos finais e parâmetros na ordem declarada. */
    public record Rendered(String subject, String body, List<String> parameters) {
    }

    /** Variáveis usadas no texto, na ordem de aparição. */
    public static Set<String> placeholders(String text) {
        Set<String> names = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    public static void validateDefinition(String subject, String body, List<TemplateVariable> variables) {
        Guard.require(body != null && !body.isBlank(), "template.body_required", "Corpo do template obrigatório");
        Guard.require(variables.size() <= MAX_VARIABLES, "template.too_many_variables",
                "No máximo %d variáveis por template".formatted(MAX_VARIABLES));
        Set<String> declared = new HashSet<>();
        for (TemplateVariable v : variables) {
            Guard.require(declared.add(v.name()), "template.variable_duplicated", "Variável repetida: " + v.name());
        }
        Set<String> used = new LinkedHashSet<>(placeholders(subject));
        used.addAll(placeholders(body));
        for (String name : used) {
            Guard.require(declared.contains(name), "template.variable_undeclared", "Variável não declarada: " + name);
        }
        for (String name : declared) {
            Guard.require(used.contains(name), "template.variable_unused", "Variável declarada e não usada: " + name);
        }
    }

    public static Rendered render(String subject, String body, List<TemplateVariable> variables, Map<String, String> values) {
        Map<String, String> input = values == null ? Map.of() : values;
        Set<String> declared = new HashSet<>();
        variables.forEach(v -> declared.add(v.name()));
        for (String key : input.keySet()) {
            Guard.require(declared.contains(key), "template.variable_unknown", "Variável não prevista no template: " + key);
        }
        java.util.HashMap<String, String> formatted = new java.util.HashMap<>();
        List<String> parameters = new ArrayList<>();
        for (TemplateVariable v : variables) {
            String raw = input.get(v.name());
            String value = raw == null || raw.isBlank() ? null : format(v, raw.trim());
            if (value == null) {
                Guard.require(!v.required(), "template.variable_required", "Valor obrigatório para " + v.name());
                value = "";
            }
            formatted.put(v.name(), value);
            parameters.add(value);
        }
        return new Rendered(subject == null ? null : substitute(subject, formatted), substitute(body, formatted), parameters);
    }

    /** Verifica se um texto livre contém documento, e-mail ou telefone. */
    public static boolean containsPersonalData(String text) {
        return text != null && (FORMATTED_DOCUMENT.matcher(text).find() || BARE_DOCUMENT.matcher(text).find()
                || EMAIL.matcher(text).find() || PHONE.matcher(text).find());
    }

    /**
     * Para códigos (linha digitável, protocolo), que são sequências numéricas por natureza: só
     * documentos formatados e e-mails são recusados.
     */
    public static boolean containsFormattedPersonalData(String text) {
        return text != null && (FORMATTED_DOCUMENT.matcher(text).find() || EMAIL.matcher(text).find());
    }

    private static String format(TemplateVariable v, String value) {
        Guard.require(value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\t') < 0,
                "template.variable_multiline", "Valor de %s não pode ter quebra de linha".formatted(v.name()));
        Guard.require(value.length() <= v.effectiveMaxLength(), "template.variable_too_long",
                "Valor de %s com mais de %d caracteres".formatted(v.name(), v.effectiveMaxLength()));
        return switch (v.type()) {
            case FIRST_NAME -> {
                Guard.require(FIRST_NAME.matcher(value).matches(), "template.variable_first_name_invalid",
                        "%s deve conter apenas o primeiro nome".formatted(v.name()));
                yield value;
            }
            case MONEY -> money(v, value);
            case DATE -> date(v, value);
            case INTEGER -> {
                Guard.require(INTEGER.matcher(value).matches(), "template.variable_integer_invalid",
                        "%s deve ser um número inteiro".formatted(v.name()));
                yield value;
            }
            case URL -> url(v, value);
            case CODE -> {
                Guard.require(CODE.matcher(value).matches(), "template.variable_code_invalid",
                        "%s aceita letras, números e . : / _ -".formatted(v.name()));
                if (containsFormattedPersonalData(value)) {
                    throw new BusinessRuleException("template.variable_personal_data",
                            "%s não pode conter documento ou e-mail".formatted(v.name()));
                }
                yield value;
            }
            case TEXT -> {
                requireNoPersonalData(v, value);
                yield value;
            }
        };
    }

    private static String money(TemplateVariable v, String value) {
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2, java.math.RoundingMode.UNNECESSARY);
            DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
            return "R$ " + format.format(amount);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new BusinessRuleException("template.variable_money_invalid",
                    "%s deve ser um valor com até 2 casas decimais (ex.: 1234.56)".formatted(v.name()));
        }
    }

    private static String date(TemplateVariable v, String value) {
        try {
            return LocalDate.parse(value).format(DISPLAY_DATE);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("template.variable_date_invalid",
                    "%s deve ser uma data no formato AAAA-MM-DD".formatted(v.name()));
        }
    }

    private static String url(TemplateVariable v, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("template.variable_url_invalid", "%s não é um endereço válido".formatted(v.name()));
        }
        Guard.require("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null,
                "template.variable_url_invalid", "%s deve ser um endereço https://".formatted(v.name()));
        requireNoPersonalData(v, value);
        return value;
    }

    private static void requireNoPersonalData(TemplateVariable v, String value) {
        if (containsPersonalData(value)) {
            throw new BusinessRuleException("template.variable_personal_data",
                    "%s não pode conter documento, e-mail ou telefone".formatted(v.name()));
        }
    }

    private static String substitute(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }
}
