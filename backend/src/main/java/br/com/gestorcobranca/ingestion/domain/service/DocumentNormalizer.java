package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Document;

/**
 * Normaliza o documento do devedor recebido na carga. Planilhas e CSVs exportados de planilha
 * frequentemente perdem os zeros à esquerda de CPF/CNPJ gravados como número
 * ({@code 01234567890} → {@code 1234567890}); a normalização os recompõe e os dígitos
 * verificadores confirmam o resultado.
 * <ul>
 *   <li>9 ou 10 dígitos → CPF completado para 11 (salvo se o tipo de pessoa informado for jurídica);</li>
 *   <li>12 ou 13 dígitos, ou menos de 14 com tipo jurídica → CNPJ completado para 14;</li>
 *   <li>a máscara (pontos, traço, barra) é ignorada.</li>
 * </ul>
 */
public final class DocumentNormalizer {

    private static final int CPF_LENGTH = 11;
    private static final int CNPJ_LENGTH = 14;
    private static final int MIN_PADDABLE = 9;

    private DocumentNormalizer() {
    }

    /**
     * @param company tipo de pessoa informado na linha: verdadeiro = jurídica, falso = física, nulo = não informado
     * @throws BusinessRuleException ({@code document.invalid}) se não for CPF/CNPJ válido
     */
    public static Result normalize(String raw, Boolean company) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() < MIN_PADDABLE || digits.length() > CNPJ_LENGTH) {
            throw new BusinessRuleException("document.invalid", "Documento deve ter 11 (CPF) ou 14 (CNPJ) dígitos");
        }
        int target;
        if (Boolean.TRUE.equals(company)) {
            target = CNPJ_LENGTH;
        } else if (digits.length() <= CPF_LENGTH) {
            target = CPF_LENGTH;
        } else {
            target = CNPJ_LENGTH;
        }
        Document document = Document.parse("0".repeat(Math.max(0, target - digits.length())) + digits);
        return new Result(document, digits.length() < target);
    }

    /** @param padded zeros à esquerda foram acrescentados */
    public record Result(Document document, boolean padded) {
    }
}
