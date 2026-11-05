package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface BankStatementLineJpa extends JpaRepository<BankStatementLine, UUID> {

    boolean existsByFingerprint(String fingerprint);

    Page<BankStatementLine> findByImportId(UUID importId, Pageable pageable);

    @Query("""
            select l.lineNumber from BankStatementLine l
             where l.importId = :importId and l.lineNumber between :fromLine and :toLine
            """)
    List<Integer> lineNumbers(@Param("importId") UUID importId, @Param("fromLine") int fromLine,
                              @Param("toLine") int toLine);

    @Query("select l.status, count(l) from BankStatementLine l where l.importId = :importId group by l.status")
    List<Object[]> countByStatus(@Param("importId") UUID importId);
}
