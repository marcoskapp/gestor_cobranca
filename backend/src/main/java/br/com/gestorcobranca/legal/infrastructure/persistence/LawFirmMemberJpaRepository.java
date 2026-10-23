package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.LawFirmMember;
import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface LawFirmMemberJpaRepository extends JpaRepository<LawFirmMember, UUID>, LawFirmMemberRepository {
}
