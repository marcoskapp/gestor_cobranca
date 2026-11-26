package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Buscas da carga pelo identificador do título e do contrato no credor sob as duas formas gravadas — a crua e a
 * anonimizada ({@code anon:} + HMAC, decisão 1476) —, sempre, sem depender do detector de documento (que muda com o
 * tempo). Assim o título trocado continua reconhecido: a linha que o traz de volta é recusada e não cria outro título
 * com o identificador cru. O resultado volta pelo identificador informado. Identificador com o prefixo reservado não
 * alcança nada.
 *
 * <p>Só a carga e a recusa do titular anonimizado comparam pela forma anonimizada; as buscas de quem consulta (busca
 * rápida, API pública, pagamento do credor) ficam só no identificador cru e não acham o título trocado (achar o
 * titular eliminado pelo identificador com o CPF seria reidentificação, decisão 1611).
 */
@Component
class ExternalIdLookup {

    private final ExternalIdDigest digest;
    private final CurrentActor currentActor;
    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;

    ExternalIdLookup(ExternalIdDigest digest, CurrentActor currentActor, DebtTitleRepository titles,
                     OriginContractRepository contracts) {
        this.digest = digest;
        this.currentActor = currentActor;
        this.titles = titles;
        this.contracts = contracts;
    }

    /** Título da carga, com trava, sob qualquer das formas; o trocado prevalece (é o que a carga precisa recusar). */
    Optional<DebtTitle> titleForUpdate(UUID creditorId, String externalId) {
        Set<String> stored = digest.candidates(currentActor.tenantId(), Kind.TITLE, creditorId, externalId);
        return stored.isEmpty() ? Optional.empty() : anonymizedFirst(
                titles.findByCreditorIdAndExternalIdInForUpdate(creditorId, stored), DebtTitle::getExternalId);
    }

    /** Contrato da carga sob qualquer das formas; o trocado prevalece. */
    Optional<OriginContract> contract(UUID creditorId, String externalId) {
        Set<String> stored = digest.candidates(currentActor.tenantId(), Kind.CONTRACT, creditorId, externalId);
        return stored.isEmpty() ? Optional.empty() : anonymizedFirst(
                contracts.findByCreditorIdAndExternalIdIn(creditorId, stored), OriginContract::getExternalId);
    }

    /** Dentre os identificadores informados (canônicos), os de títulos do credor com o devedor anonimizado. */
    Set<String> anonymizedTitleKeys(UUID creditorId, Collection<String> externalIds) {
        Map<String, String> byStored = candidates(Kind.TITLE, creditorId, externalIds);
        return byStored.isEmpty() ? Set.of()
                : inputsOf(titles.findExternalIdsOfAnonymizedDebtors(creditorId, byStored.keySet()), byStored);
    }

    /** Dentre os identificadores informados (canônicos), os de contratos do credor com o devedor anonimizado. */
    Set<String> anonymizedContractKeys(UUID creditorId, Collection<String> externalIds) {
        Map<String, String> byStored = candidates(Kind.CONTRACT, creditorId, externalIds);
        return byStored.isEmpty() ? Set.of()
                : inputsOf(contracts.findExternalIdsOfAnonymizedDebtors(creditorId, byStored.keySet()), byStored);
    }

    /** Devedores anonimizados donos destes identificadores de título e de contrato no credor. */
    Set<UUID> anonymizedDebtorIds(UUID creditorId, Collection<String> titleExternalIds,
                                  Collection<String> contractExternalIds) {
        Set<UUID> ids = new LinkedHashSet<>();
        Map<String, String> titleStored = candidates(Kind.TITLE, creditorId, titleExternalIds);
        if (!titleStored.isEmpty()) {
            ids.addAll(titles.findAnonymizedDebtorIds(creditorId, titleStored.keySet()));
        }
        Map<String, String> contractStored = candidates(Kind.CONTRACT, creditorId, contractExternalIds);
        if (!contractStored.isEmpty()) {
            ids.addAll(contracts.findAnonymizedDebtorIds(creditorId, contractStored.keySet()));
        }
        return ids;
    }

    /** Valor gravado candidato → identificador informado (canônico). */
    private Map<String, String> candidates(Kind kind, UUID creditorId, Collection<String> externalIds) {
        Map<String, String> byStored = new HashMap<>();
        if (externalIds == null) {
            return byStored;
        }
        UUID tenantId = currentActor.tenantId();
        externalIds.stream().map(ExternalIdDigest::canonical).filter(Objects::nonNull).distinct()
                .forEach(id -> digest.candidates(tenantId, kind, creditorId, id).forEach(s -> byStored.put(s, id)));
        return byStored;
    }

    private static Set<String> inputsOf(List<String> stored, Map<String, String> byStored) {
        Set<String> out = new LinkedHashSet<>();
        stored.forEach(s -> {
            String input = byStored.get(s);
            if (input != null) {
                out.add(input);
            }
        });
        return out;
    }

    private static <T> Optional<T> anonymizedFirst(List<T> found, Function<T, String> stored) {
        return found.stream()
                .min(Comparator.comparing((T t) -> ExternalIdDigest.isAnonymized(stored.apply(t)) ? 0 : 1));
    }
}
