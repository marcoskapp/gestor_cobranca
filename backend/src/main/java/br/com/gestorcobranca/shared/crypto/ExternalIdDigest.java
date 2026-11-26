package br.com.gestorcobranca.shared.crypto;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Forma anonimizada do identificador do título e do contrato no credor (SEG-20261125-K02, decisão 1476): {@code anon:}
 * seguido do HMAC ({@link BlindIndex}, com a assessoria e a finalidade) do credor e do identificador canônico.
 * Determinística, para que a carga e a detecção nos arquivos reconheçam o título já trocado; o credor entra no
 * resumo porque o identificador só é único dentro dele. A mesma chave ({@link #key}) é gravada nas linhas da carga
 * ({@code ingestion.import_batch_row.title_key}) para a detecção nos arquivos, sem o identificador cru sair do debt.
 *
 * <p>Girar a chave de blind index faz a carga e a detecção deixarem de reconhecer os identificadores já trocados (o
 * cru não existe mais para recalcular): a rotação precisa manter a chave anterior só para verificação. O valor sem
 * versão é da chave 1; {@code anon:<versão>:} fica reservado para as seguintes (SEG-20261126-E10).
 */
@Component
public class ExternalIdDigest {

    public static final String PREFIX = "anon:";
    private static final Pattern ANONYMIZED = Pattern.compile("anon:[0-9a-f]{64}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    /** Finalidade do resumo: título e contrato com o mesmo identificador dão valores diferentes. */
    public enum Kind {
        TITLE("debt.title.external_id"),
        CONTRACT("debt.origin_contract.external_id");

        private final String purpose;

        Kind(String purpose) {
            this.purpose = purpose;
        }
    }

    private final BlindIndex blindIndex;

    public ExternalIdDigest(BlindIndex blindIndex) {
        this.blindIndex = blindIndex;
    }

    /**
     * Identificador como a carga e o cadastro o gravam: sem espaços nas pontas ({@code strip}, como a leitura da linha);
     * vazio vira nulo. Caractere de controle não é removido: é recusado na entrada ({@link #hasControl}, decisão 1622).
     */
    public static String canonical(String externalId) {
        if (externalId == null) {
            return null;
        }
        String stripped = externalId.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Identificador com caractere de controle: recusado na entrada (SEG-20261126-X11). */
    public static boolean hasControl(String externalId) {
        return externalId != null && CONTROL.matcher(externalId).find();
    }

    /** O prefixo é reservado: identificador informado que começa com ele é recusado (sem distinguir maiúsculas). */
    public static boolean isReserved(String externalId) {
        String c = canonical(externalId);
        return c != null && c.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /** Valor gravado já trocado pela forma anonimizada. */
    public static boolean isAnonymized(String stored) {
        return stored != null && ANONYMIZED.matcher(stored).matches();
    }

    /** A chave (64 hex) de um valor já trocado; nulo se o valor não foi trocado. */
    public static String keyOfAnonymized(String stored) {
        return isAnonymized(stored) ? stored.substring(PREFIX.length()) : null;
    }

    /** Chave HMAC (64 hex) do identificador no credor; nula para identificador vazio. */
    public String key(UUID tenantId, Kind kind, UUID creditorId, String externalId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(creditorId, "creditorId");
        String c = canonical(externalId);
        return c == null ? null : blindIndex.of(tenantId, kind.purpose, creditorId + "|" + c);
    }

    /** Forma anonimizada ({@code anon:} + chave, 69 caracteres). */
    public String anonymized(UUID tenantId, Kind kind, UUID creditorId, String externalId) {
        String key = key(tenantId, kind, creditorId, externalId);
        return key == null ? null : PREFIX + key;
    }

    /**
     * As duas formas sob as quais o identificador pode estar gravado: a crua e a anonimizada. Toda busca vinda da carga
     * compara pelas duas, sempre, sem depender do detector de documento (que muda com o tempo). Identificador com o
     * prefixo reservado não gera candidato (nunca alcança o registro trocado).
     */
    public Set<String> candidates(UUID tenantId, Kind kind, UUID creditorId, String externalId) {
        String c = canonical(externalId);
        if (c == null || isReserved(c)) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        out.add(c);
        out.add(anonymized(tenantId, kind, creditorId, c));
        return out;
    }
}
