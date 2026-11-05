package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputePoolMember;
import br.com.gestorcobranca.collection.domain.repository.DisputePoolMemberRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputePoolMemberJpaRepository extends JpaRepository<DisputePoolMember, UUID>, DisputePoolMemberRepository {
}
