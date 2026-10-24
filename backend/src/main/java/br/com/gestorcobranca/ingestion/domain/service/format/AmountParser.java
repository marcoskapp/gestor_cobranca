package br.com.gestorcobranca.ingestion.domain.service.format;

import br.com.gestorcobranca.ingestion.domain.model.format.AmountProblem;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountReading;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountStyle;
import br.com.gestorcobranca.ingestion.domain.model.format.DecimalConvention;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Leitura de valores monetários (regras em {@code docs/tecnico/desenho/importacao.md} §2.1). Função pura.
 * <p>
 * O texto é primeiro <b>classificado</b> ({@link #lex(String)}) sem escolher convenção: o que o valor prova sobre o
 * separador decimal da coluna. Depois é <b>convertido</b> sob uma convenção ou opção ({@link #read}). Nenhum valor é
 * convertido "adivinhando": quem escolhe a convenção é a análise da coluna inteira ou o usuário.
 * <p>
 * Valores tipados (célula numérica do XLSX, número JSON) chegam em texto canônico ({@code -1234.56}, sem milhar) e não
 * passam pela classificação.
 */
public final class AmountParser {

    /** Casas decimais de um valor monetário. */
    public static final int MAX_DECIMALS = 2;
    /** Dígitos inteiros aceitos (numeric(19,2)). */
    public static final int MAX_INTEGER_DIGITS = 17;

    private static final Pattern SCIENTIFIC = Pattern.compile("[0-9.,]*[0-9][eE][+-]?[0-9]+");
    private static final Pattern BODY = Pattern.compile("[0-9.,]+");
    private static final Pattern TYPED = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final String SPACES = "    ";

    private AmountParser() {
    }

    /**
     * Texto de um valor já sem sinal, moeda e espaços de milhar.
     *
     * @param body         dígitos e separadores {@code .}/{@code ,} (vazio se inválido)
     * @param spaceGrouped o milhar veio com espaços ({@code 1 000,00}): {@code .} ou {@code ,} só podem ser decimal
     */
    public record Token(AmountStyle style, boolean negative, String body, boolean spaceGrouped, AmountProblem problem) {

        static Token invalid(AmountProblem problem) {
            return new Token(AmountStyle.INVALID, false, "", false, problem);
        }
    }

    /** Classifica um valor em texto. */
    public static Token lex(String raw) {
        if (raw == null) {
            return Token.invalid(AmountProblem.FORMAT);
        }
        String s = raw.strip();
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        s = stripCurrency(s);
        if (!s.isEmpty() && (s.charAt(0) == '-' || s.charAt(0) == '+')) {
            if (s.charAt(0) == '-') {
                if (negative) {
                    return Token.invalid(AmountProblem.FORMAT);
                }
                negative = true;
            }
            s = stripCurrency(s.substring(1).strip());
        }
        if (s.endsWith("-")) {
            if (negative) {
                return Token.invalid(AmountProblem.FORMAT);
            }
            negative = true;
            s = stripCurrency(s.substring(0, s.length() - 1).strip());
        }
        if (s.isEmpty()) {
            return Token.invalid(AmountProblem.FORMAT);
        }
        String compact = removeSpaces(s);
        if (SCIENTIFIC.matcher(compact).matches()) {
            return Token.invalid(AmountProblem.SCIENTIFIC_NOTATION);
        }
        boolean spaceGrouped = !compact.equals(s);
        if (spaceGrouped && !validSpaceGrouping(s)) {
            return Token.invalid(AmountProblem.FORMAT);
        }
        if (!BODY.matcher(compact).matches() || isSeparator(compact.charAt(compact.length() - 1))) {
            return Token.invalid(AmountProblem.FORMAT);
        }
        AmountStyle style = classify(compact, spaceGrouped);
        return style == AmountStyle.INVALID ? Token.invalid(AmountProblem.FORMAT)
                : new Token(style, negative, compact, spaceGrouped, null);
    }

    private static AmountStyle classify(String body, boolean spaceGrouped) {
        int dots = count(body, '.');
        int commas = count(body, ',');
        if (dots == 0 && commas == 0) {
            return AmountStyle.PLAIN;
        }
        if (dots > 0 && commas > 0) {
            if (spaceGrouped) {
                return AmountStyle.INVALID;
            }
            char last = body.lastIndexOf('.') > body.lastIndexOf(',') ? '.' : ',';
            char other = last == '.' ? ',' : '.';
            int decimalAt = body.lastIndexOf(last);
            boolean valid = count(body, last) == 1 && body.lastIndexOf(other) < decimalAt
                    && grouped(body.substring(0, decimalAt), other) && decimalAt < body.length() - 1;
            if (!valid) {
                return AmountStyle.INVALID;
            }
            return last == ',' ? AmountStyle.COMMA_DECIMAL : AmountStyle.DOT_DECIMAL;
        }
        char sep = dots > 0 ? '.' : ',';
        AmountStyle asDecimal = sep == ',' ? AmountStyle.COMMA_DECIMAL : AmountStyle.DOT_DECIMAL;
        AmountStyle asGrouping = sep == '.' ? AmountStyle.COMMA_DECIMAL : AmountStyle.DOT_DECIMAL;
        if (count(body, sep) > 1) {
            // separador repetido só pode ser milhar
            return !spaceGrouped && grouped(body, sep) ? asGrouping : AmountStyle.INVALID;
        }
        if (spaceGrouped) {
            return asDecimal;
        }
        int at = body.indexOf(sep);
        String integer = body.substring(0, at);
        int fraction = body.length() - at - 1;
        if (fraction != 3) {
            // milhar tem sempre três dígitos
            return asDecimal;
        }
        if (integer.isEmpty() || integer.length() > 3 || integer.charAt(0) == '0') {
            // ",500", "1234.567" e "0.500" não são agrupamento válido: só podem ser decimal
            return asDecimal;
        }
        return sep == '.' ? AmountStyle.AMBIGUOUS_DOT : AmountStyle.AMBIGUOUS_COMMA;
    }

    /** Lê um valor em texto sob a opção da coluna. */
    public static AmountReading read(String raw, FormatOption option) {
        Token token = lex(raw);
        if (token.style() == AmountStyle.INVALID) {
            return AmountReading.failed(token.problem(), AmountStyle.INVALID);
        }
        return convert(token, option.conventionFor(token.style()));
    }

    /** Lê um valor (tipado ou em texto) sob a opção da coluna. */
    public static AmountReading read(String raw, boolean typed, FormatOption option) {
        return typed ? readTyped(raw) : read(raw, option);
    }

    /** Converte um valor já classificado sob uma convenção. */
    public static AmountReading convert(Token token, DecimalConvention convention) {
        if (token.style() == AmountStyle.INVALID) {
            return AmountReading.failed(token.problem(), AmountStyle.INVALID);
        }
        String body = token.body();
        char decimal = convention.decimal();
        char grouping = convention.grouping();
        int decimalAt = body.indexOf(decimal);
        if (decimalAt >= 0 && body.indexOf(decimal, decimalAt + 1) >= 0) {
            return AmountReading.failed(AmountProblem.FORMAT, token.style());
        }
        String integer = decimalAt < 0 ? body : body.substring(0, decimalAt);
        String fraction = decimalAt < 0 ? "" : body.substring(decimalAt + 1);
        if (fraction.indexOf(grouping) >= 0) {
            return AmountReading.failed(AmountProblem.FORMAT, token.style());
        }
        if (integer.indexOf(grouping) >= 0) {
            if (token.spaceGrouped() || !grouped(integer, grouping)) {
                return AmountReading.failed(AmountProblem.FORMAT, token.style());
            }
            integer = integer.replace(String.valueOf(grouping), "");
        }
        return assemble(integer, fraction, token.negative(), token.style());
    }

    /** Valor tipado em texto canônico ({@code -1234.56}). */
    public static AmountReading readTyped(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!TYPED.matcher(value).matches()) {
            return AmountReading.failed(AmountProblem.FORMAT, AmountStyle.TYPED);
        }
        boolean negative = value.startsWith("-");
        String digits = negative ? value.substring(1) : value;
        int dot = digits.indexOf('.');
        return assemble(dot < 0 ? digits : digits.substring(0, dot), dot < 0 ? "" : digits.substring(dot + 1), negative,
                AmountStyle.TYPED);
    }

    private static AmountReading assemble(String integer, String fraction, boolean negative, AmountStyle style) {
        if (fraction.length() > MAX_DECIMALS) {
            if (!fraction.substring(MAX_DECIMALS).chars().allMatch(c -> c == '0')) {
                return AmountReading.failed(AmountProblem.TOO_MANY_DECIMALS, style);
            }
            fraction = fraction.substring(0, MAX_DECIMALS);
        }
        String significant = integer.replaceFirst("^0+", "");
        if (significant.length() > MAX_INTEGER_DIGITS) {
            return AmountReading.failed(AmountProblem.TOO_LARGE, style);
        }
        BigDecimal value = new BigDecimal((integer.isEmpty() ? "0" : integer) + (fraction.isEmpty() ? "" : "." + fraction))
                .setScale(MAX_DECIMALS, RoundingMode.UNNECESSARY);
        return AmountReading.of(negative ? value.negate() : value, style);
    }

    /**
     * {@code [1-9]\d{0,2}(S\d{3})+} — agrupamento de milhar válido com o separador {@code sep} (o primeiro grupo não
     * começa com zero: {@code 0.500} não é quinhentos).
     */
    static boolean grouped(String integer, char sep) {
        String[] groups = integer.split(Pattern.quote(String.valueOf(sep)), -1);
        if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3 || groups[0].charAt(0) == '0') {
            return false;
        }
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3) {
                return false;
            }
        }
        return true;
    }

    /** Espaços só como milhar, antes de qualquer separador decimal: {@code 1 000}, {@code 12 345,67}. */
    private static boolean validSpaceGrouping(String s) {
        int firstSep = firstSeparator(s);
        String integer = firstSep < 0 ? s : s.substring(0, firstSep);
        String rest = firstSep < 0 ? "" : s.substring(firstSep);
        if (rest.chars().anyMatch(c -> SPACES.indexOf(c) >= 0)) {
            return false;
        }
        StringBuilder normalized = new StringBuilder();
        for (char c : integer.toCharArray()) {
            normalized.append(SPACES.indexOf(c) >= 0 ? ' ' : c);
        }
        return normalized.toString().matches("\\d{1,3}( \\d{3})+");
    }

    private static String stripCurrency(String s) {
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.startsWith("R$")) {
            return s.substring(2).strip();
        }
        if (upper.endsWith("R$")) {
            return s.substring(0, s.length() - 2).strip();
        }
        return s;
    }

    private static String removeSpaces(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (SPACES.indexOf(c) < 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int firstSeparator(String s) {
        int dot = s.indexOf('.');
        int comma = s.indexOf(',');
        if (dot < 0) {
            return comma;
        }
        return comma < 0 ? dot : Math.min(dot, comma);
    }

    private static boolean isSeparator(char c) {
        return c == '.' || c == ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
