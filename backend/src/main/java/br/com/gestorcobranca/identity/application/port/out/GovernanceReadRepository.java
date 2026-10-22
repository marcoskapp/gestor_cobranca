package br.com.gestorcobranca.identity.application.port.out;

import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AccessLogEntry;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AccessLogFilter;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AuditFilter;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AuditLogEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Leitura do log de acesso e da trilha de auditoria (tabelas do kernel, filtradas por RLS). */
public interface GovernanceReadRepository {

    Page<AccessLogEntry> accessLog(AccessLogFilter filter, Pageable pageable);

    Page<AuditLogEntry> auditTrail(AuditFilter filter, Pageable pageable);
}
