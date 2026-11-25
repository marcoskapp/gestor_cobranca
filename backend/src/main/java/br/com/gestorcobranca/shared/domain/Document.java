package br.com.gestorcobranca.shared.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Documento de identificação fiscal brasileiro (CPF ou CNPJ), normalizado apenas com dígitos
 * e com dígitos verificadores validados. É a chave principal de identidade de um devedor
 * (RB01): nomes iguais nunca bastam para consolidar pessoas.
 */
public record Document(DocumentType type, String number) {

    public enum DocumentType { CPF, CNPJ }

    public Document {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(number, "number");
        if (!isValid(type, number)) {
            throw new BusinessRuleException("document.invalid", "Documento %s inválido".formatted(type));
        }
    }

    /** Cria a partir de texto livre (com ou sem máscara), inferindo o tipo pelo tamanho. */
    public static Document parse(String raw) {
        if (raw == null) {
            throw new BusinessRuleException("document.required", "Documento obrigatório");
        }
        String digits = raw.replaceAll("\\D", "");
        return switch (digits.length()) {
            case 11 -> new Document(DocumentType.CPF, digits);
            case 14 -> new Document(DocumentType.CNPJ, digits);
            default -> throw new BusinessRuleException("document.invalid", "Documento deve ter 11 (CPF) ou 14 (CNPJ) dígitos");
        };
    }

    private static final Pattern DOCUMENT_TEXT = Pattern.compile("[\\d.\\-/\\s]+");
    private static final Pattern NON_DIGIT = Pattern.compile("\\D");
    /**
     * Trecho numérico: dígitos com qualquer separador que não seja letra entre eles (pontuação, espaço, tabulação,
     * espaço não separável, {@code _ : |}…), para nenhum separador esconder um documento da máscara. Linear: uma classe,
     * sem aninhamento.
     */
    private static final Pattern NUMERIC_TOKEN = Pattern.compile("[0-9](?:[^\\p{L}]*[0-9])?");

    /**
     * O texto inteiro é um CPF ou CNPJ válido (só dígitos, pontuação e espaços; 11 ou 14 dígitos com dígitos
     * verificadores corretos). Vazio para qualquer outro texto.
     */
    public static Optional<Document> detect(String text) {
        if (text == null || !DOCUMENT_TEXT.matcher(text.trim()).matches()) {
            return Optional.empty();
        }
        String digits = NON_DIGIT.matcher(text).replaceAll("");
        if (digits.length() == 11 && isValid(DocumentType.CPF, digits)
                || digits.length() == 14 && isValid(DocumentType.CNPJ, digits)) {
            return Optional.of(parse(digits));
        }
        return Optional.empty();
    }

    /**
     * Texto para exibir a terceiros sem expor documento (decisão 1472): o texto inteiro que é CPF/CNPJ vira a forma
     * mascarada; em qualquer outro texto, todo CPF/CNPJ válido que apareça em qualquer posição de um trecho numérico
     * (dígitos com ou sem pontuação e espaços — ex.: o identificador de título {@code 52998224725-001},
     * {@code 0052998224725}, {@code 529982247-25-001}) tem os mesmos dígitos de {@link #masked()} trocados por
     * {@code *}, no lugar, mantendo o resto. Mascarar por coincidência (cerca de 1% das janelas de 11 dígitos) é o lado
     * seguro. CPF sem os zeros à esquerda não é reconhecido. Só para identificadores curtos (referência de título ou
     * contrato): em texto livre, números sem relação se juntariam num trecho e seriam mascarados por coincidência.
     * Resíduo aceito: letras usadas como separador entre os grupos ({@code 529a982b247c25}) não são reconhecidas.
     */
    public static String maskIfDocument(String text) {
        if (text == null) {
            return null;
        }
        // dígitos de largura total e outras formas compatíveis viram ASCII (o texto é só para exibição)
        text = Normalizer.normalize(text, Normalizer.Form.NFKC);
        Optional<Document> whole = detect(text);
        if (whole.isPresent()) {
            return whole.get().masked();
        }
        return NUMERIC_TOKEN.matcher(text).replaceAll(m -> Matcher.quoteReplacement(maskEmbedded(m.group())));
    }

    private static String maskEmbedded(String token) {
        List<Integer> at = new ArrayList<>();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') {
                at.add(i);
                digits.append(c);
            }
        }
        // a união de todas as janelas válidas: um CNPJ que valha por coincidência não esconde só parte de um CPF real
        StringBuilder out = new StringBuilder(token);
        for (int i = 0; i + 11 <= at.size(); i++) {
            if (i + 14 <= at.size() && isValid(DocumentType.CNPJ, digits.substring(i, i + 14))) {
                hide(out, at, i, i + 1, i + 12, i + 13);
            }
            if (isValid(DocumentType.CPF, digits.substring(i, i + 11))) {
                hide(out, at, i, i + 1, i + 2, i + 9, i + 10);
            }
        }
        return out.toString();
    }

    private static void hide(StringBuilder out, List<Integer> at, int... digitIndexes) {
        for (int d : digitIndexes) {
            out.setCharAt(at.get(d), '*');
        }
    }

    public static boolean isValid(DocumentType type, String digits) {
        if (digits == null || !digits.matches("\\d+")) {
            return false;
        }
        return switch (type) {
            case CPF -> validCpf(digits);
            case CNPJ -> validCnpj(digits);
        };
    }

    /** Forma mascarada para exibição e logs: ***.456.789-** / **.345.678/0001-** */
    public String masked() {
        return switch (type) {
            case CPF -> "***." + number.substring(3, 6) + "." + number.substring(6, 9) + "-**";
            case CNPJ -> "**." + number.substring(2, 5) + "." + number.substring(5, 8) + "/" + number.substring(8, 12) + "-**";
        };
    }

    /**
     * Raiz do CNPJ: os 8 primeiros dígitos, comuns à matriz e às filiais da mesma empresa. Vazio para CPF. O agrupamento
     * por grupo econômico além da raiz depende de consulta cadastral externa (issue #31).
     */
    public java.util.Optional<String> cnpjRoot() {
        return type == DocumentType.CNPJ ? java.util.Optional.of(number.substring(0, 8)) : java.util.Optional.empty();
    }

    /** Número do estabelecimento do CNPJ (4 dígitos depois da raiz: {@code 0001} é a matriz). Vazio para CPF. */
    public java.util.Optional<String> cnpjBranch() {
        return type == DocumentType.CNPJ ? java.util.Optional.of(number.substring(8, 12)) : java.util.Optional.empty();
    }

    /** Matriz pelo sufixo do CNPJ ({@code 0001}); falso para CPF. */
    public boolean isHeadOffice() {
        return cnpjBranch().map("0001"::equals).orElse(false);
    }

    public String formatted() {
        return switch (type) {
            case CPF -> "%s.%s.%s-%s".formatted(number.substring(0, 3), number.substring(3, 6), number.substring(6, 9), number.substring(9));
            case CNPJ -> "%s.%s.%s/%s-%s".formatted(number.substring(0, 2), number.substring(2, 5), number.substring(5, 8), number.substring(8, 12), number.substring(12));
        };
    }

    private static boolean validCpf(String cpf) {
        if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(cpf, 9, 10) == cpf.charAt(9) - '0'
                && checkDigit(cpf, 10, 11) == cpf.charAt(10) - '0';
    }

    private static int checkDigit(String cpf, int length, int startWeight) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (cpf.charAt(i) - '0') * (startWeight - i);
        }
        int mod = (sum * 10) % 11;
        return mod == 10 ? 0 : mod;
    }

    private static boolean validCnpj(String cnpj) {
        if (cnpj.length() != 14 || cnpj.chars().distinct().count() == 1) {
            return false;
        }
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return cnpjDigit(cnpj, w1) == cnpj.charAt(12) - '0'
                && cnpjDigit(cnpj, w2) == cnpj.charAt(13) - '0';
    }

    private static int cnpjDigit(String cnpj, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (cnpj.charAt(i) - '0') * weights[i];
        }
        int mod = sum % 11;
        return mod < 2 ? 0 : 11 - mod;
    }

    @Override
    public String toString() {
        // nunca expor o número completo em toString (evita vazamento acidental em logs)
        return type + "(" + masked() + ")";
    }
}
