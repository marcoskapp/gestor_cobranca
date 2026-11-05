package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputePoolMember;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputePoolMemberRepository {

    DisputePoolMember save(DisputePoolMember member);

    Optional<DisputePoolMember> findById(UUID id);

    List<DisputePoolMember> findByActiveTrue();
}
