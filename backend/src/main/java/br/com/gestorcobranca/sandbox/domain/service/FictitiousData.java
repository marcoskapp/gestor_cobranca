package br.com.gestorcobranca.sandbox.domain.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Gerador determinístico de dados pessoais <strong>fictícios</strong> para o sandbox: a mesma semente produz sempre os
 * mesmos devedores (a carga é idempotente e reprodutível). CPFs e CNPJs têm dígitos verificadores válidos, mas são
 * gerados ao acaso; nomes são combinações de listas; e-mails usam o domínio reservado {@code exemplo.test} (RFC 2606/6761,
 * nunca entregue na Internet); telefones só alcançam os provedores simulados.
 */
public final class FictitiousData {

    private static final List<String> FIRST_NAMES = List.of(
            "Ana", "Bruno", "Carla", "Diego", "Elisa", "Fábio", "Giovana", "Henrique", "Isabela", "João",
            "Karina", "Leonardo", "Marina", "Nicolas", "Olívia", "Paulo", "Quitéria", "Rodrigo", "Sabrina", "Tiago",
            "Úrsula", "Vinícius", "Wanda", "Xavier", "Yasmin", "Zeca", "Aline", "Benedito", "Cecília", "Davi",
            "Estela", "Felipe", "Gabriela", "Hugo", "Iara", "Jonas", "Lívia", "Mateus", "Natália", "Otávio");
    private static final List<String> LAST_NAMES = List.of(
            "Albuquerque", "Barbosa", "Cardoso", "Dantas", "Esteves", "Figueira", "Guimarães", "Holanda", "Ipanema",
            "Jardim", "Lacerda", "Macedo", "Nascimento", "Oliveira", "Pacheco", "Queiroz", "Ramalho", "Siqueira",
            "Tavares", "Uchoa", "Valadares", "Xavante", "Zanetti", "Amaral", "Bittencourt", "Correia", "Damasceno",
            "Escobar", "Fontoura", "Gusmão", "Herculano", "Itaparica", "Junqueira", "Lobato", "Meireles", "Novaes",
            "Ornelas", "Prudente", "Quintela", "Resende");
    private static final List<String> COMPANY_WORDS = List.of(
            "Aurora", "Cedro", "Delta", "Estrela", "Farol", "Girassol", "Horizonte", "Ipê", "Jacarandá", "Laguna",
            "Maré", "Nascente", "Orvalho", "Planalto", "Quintal", "Ribeira", "Serra", "Trilha", "Vale", "Zênite");
    private static final List<String> COMPANY_KINDS = List.of(
            "Comércio de Alimentos", "Serviços Gráficos", "Transportes", "Materiais de Construção", "Padaria",
            "Oficina Mecânica", "Papelaria", "Distribuidora", "Confecções", "Climatização");
    /** DDDs válidos (a validação de telefone do domínio confere o código de área). */
    private static final int[] AREA_CODES = {11, 21, 31, 41, 51, 61, 71, 81, 85, 27, 48, 62, 92, 91, 19, 47};
    private static final String PLATE_LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ";

    private final Random random;

    public FictitiousData(long seed) {
        this.random = new Random(seed);
    }

    public int between(int minInclusive, int maxInclusive) {
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    public boolean chance(int percent) {
        return random.nextInt(100) < percent;
    }

    public <T> T pick(List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    /** CPF (11 dígitos, sem máscara) com dígitos verificadores válidos. */
    public String cpf() {
        int[] d = new int[11];
        do {
            for (int i = 0; i < 9; i++) {
                d[i] = random.nextInt(10);
            }
        } while (allEqual(d, 9));
        d[9] = cpfDigit(d, 9);
        d[10] = cpfDigit(d, 10);
        return join(d);
    }

    /** CNPJ (14 dígitos, sem máscara; matriz 0001) com dígitos verificadores válidos. */
    public String cnpj() {
        int[] d = new int[14];
        do {
            for (int i = 0; i < 8; i++) {
                d[i] = random.nextInt(10);
            }
        } while (allEqual(d, 8));
        d[8] = 0;
        d[9] = 0;
        d[10] = 0;
        d[11] = 1;
        d[12] = cnpjDigit(d, 12);
        d[13] = cnpjDigit(d, 13);
        return join(d);
    }

    public String personName() {
        String last = pick(LAST_NAMES);
        String middle = pick(LAST_NAMES);
        return pick(FIRST_NAMES) + " " + (middle.equals(last) ? "" : middle + " ") + last;
    }

    public String companyName() {
        return pick(COMPANY_KINDS) + " " + pick(COMPANY_WORDS) + " Ltda";
    }

    /** E-mail no domínio reservado {@code exemplo.test}; o sufixo numérico evita colisões entre homônimos. */
    public String email(String name, int sequence) {
        String[] parts = slug(name).split("-");
        String local = parts[0] + "." + parts[parts.length - 1] + "." + sequence;
        return local + "@exemplo.test";
    }

    /** Celular brasileiro no formato E.164 ({@code +55DD9XXXXXXXX}). */
    public String mobilePhone() {
        int area = AREA_CODES[random.nextInt(AREA_CODES.length)];
        StringBuilder sb = new StringBuilder("+55").append(area).append("99");
        for (int i = 0; i < 7; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    public LocalDate birthDate(LocalDate today) {
        return today.minusYears(between(21, 75)).minusDays(between(0, 364));
    }

    /** Placa no padrão Mercosul (AAA9A99). */
    public String plate() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(PLATE_LETTERS.charAt(random.nextInt(PLATE_LETTERS.length())));
        }
        sb.append(random.nextInt(10)).append(PLATE_LETTERS.charAt(random.nextInt(PLATE_LETTERS.length())));
        sb.append(random.nextInt(10)).append(random.nextInt(10));
        return sb.toString();
    }

    /** Texto em minúsculas, sem acentos, com hífens (para códigos e e-mails). */
    public static String slug(String text) {
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static int cpfDigit(int[] d, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += d[i] * (length + 1 - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }

    private static int cnpjDigit(int[] d, int length) {
        int[] weights = length == 12 ? new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}
                : new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += d[i] * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    private static boolean allEqual(int[] d, int length) {
        for (int i = 1; i < length; i++) {
            if (d[i] != d[0]) {
                return false;
            }
        }
        return true;
    }

    private static String join(int[] d) {
        StringBuilder sb = new StringBuilder(d.length);
        for (int v : d) {
            sb.append(v);
        }
        return sb.toString();
    }
}
