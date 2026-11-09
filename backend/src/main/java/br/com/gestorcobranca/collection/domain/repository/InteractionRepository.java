package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.Interaction;
import br.com.gestorcobranca.collection.domain.model.InteractionSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface InteractionRepository {

    Interaction save(Interaction interaction);

    Optional<Interaction> findById(UUID id);

    Optional<Interaction> findBySourceAndExternalRef(InteractionSource source, String externalRef);

    Page<Interaction> findByCaseIdOrderByStartedAtDesc(UUID caseId, Pageable pageable);

    /**
     * Interações do devedor, da mais recente à mais antiga ({@code ix_interaction_debtor}, V0607).
     *
     * @param allowedCreditorIds credores do escopo; {@code null} = todos (vazio não é aceito: o chamador devolve vazio)
     */
    Page<Interaction> findByDebtor(UUID debtorId, Collection<UUID> allowedCreditorIds, Pageable pageable);
}
