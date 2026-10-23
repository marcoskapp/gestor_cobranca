package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ResponseTarget;

import java.util.List;
import java.util.Optional;

/** Metas de primeira resposta da assessoria corrente (no máximo uma por canal; RLS restringe ao tenant). */
public interface ResponseTargetRepository {

    ResponseTarget save(ResponseTarget target);

    List<ResponseTarget> findAll();

    Optional<ResponseTarget> findByChannel(ChannelType channel);
}
