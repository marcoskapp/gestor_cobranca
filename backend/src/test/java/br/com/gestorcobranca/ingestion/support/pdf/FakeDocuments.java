package br.com.gestorcobranca.ingestion.support.pdf;

/** CPF e CNPJ fictícios com dígitos verificadores válidos, gerados a partir de uma semente. */
public final class FakeDocuments {

    private FakeDocuments() {
    }

    public static String cpf(int seed) {
        String base = "%09d".formatted(200_000_000 + seed);
        int d1 = mod11(base, new int[]{10, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = mod11(base + d1, new int[]{11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    public static String cnpj(int seed) {
        String base = "%08d".formatted(30_000_000 + seed) + "0001";
        int d1 = mod11(base, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = mod11(base + d1, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    public static String maskedCnpj(String digits) {
        return digits.substring(0, 2) + "." + digits.substring(2, 5) + "." + digits.substring(5, 8) + "/"
                + digits.substring(8, 12) + "-" + digits.substring(12);
    }

    public static String maskedCpf(String digits) {
        return digits.substring(0, 3) + "." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-"
                + digits.substring(9);
    }

    private static int mod11(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
