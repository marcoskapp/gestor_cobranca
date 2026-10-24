package br.com.gestorcobranca.telephony.domain.repository;

import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallRepository {

    Call save(Call call);

    Call saveAndFlush(Call call);

    Optional<Call> findById(UUID id);

    /** Com lock pessimista: serializa eventos do provedor, tabulação e encerramento da mesma chamada. */
    Optional<Call> findByIdForUpdate(UUID id);

    Optional<Call> findByProviderAndProviderCallId(ProviderType provider, String providerCallId);

    List<Call> findAllByIdIn(Collection<UUID> ids);

    /** Chamadas do operador aguardando tabulação, da mais antiga para a mais recente. */
    List<Call> findAwaitingTabulation(UUID operatorId);

    /** Chamadas da campanha ainda sem operador (na fila, discando, tocando ou atendidas aguardando conexão). */
    long countUnconnectedActiveByCampaign(UUID campaignId);

    /** Últimas chamadas identificadas (com caso) ao número (blind index), da mais recente para a mais antiga. */
    List<Call> findIdentifiedByNumberHash(String numberHash, int limit);

    /** Ids das chamadas do devedor com número ainda guardado (anonimização). */
    List<UUID> findIdsWithNumberByDebtor(UUID debtorId);

    /** Chamadas em andamento sem evento do provedor há mais que o limite (conciliação). */
    List<UUID> findStaleActiveIds(Instant before, int limit);

    Page<Call> search(CallSearchCriteria criteria, Pageable pageable);
}
