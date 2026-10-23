package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LawFirmMember;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LawFirmMemberRepository {

    LawFirmMember save(LawFirmMember member);

    Optional<LawFirmMember> findByUserIdAndActiveTrue(UUID userId);

    List<LawFirmMember> findByLawFirmIdOrderByJoinedAtAsc(UUID lawFirmId);

    Optional<LawFirmMember> findByLawFirmIdAndUserIdAndActiveTrue(UUID lawFirmId, UUID userId);
}
