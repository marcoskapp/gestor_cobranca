package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.TenantPlatformBillingUseCases;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

/**
 * A cobrança da plataforma vista pela própria assessoria (somente leitura): o mesmo motor, no tenant da sessão e sob
 * RLS — a assessoria só alcança a própria política e os próprios extratos. Exige {@code TENANT_BILLING_VIEW} e acesso
 * a todos os credores, porque a posição e o extrato somam (e detalham) o consumo de todos eles.
 */
@Service
@Transactional(readOnly = true)
class TenantPlatformBillingService implements TenantPlatformBillingUseCases {

    private static final String RESOURCE = "platform_statement";

    private final PlatformBillingEngine engine;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    TenantPlatformBillingService(PlatformBillingEngine engine, AccessScope accessScope, CurrentActor currentActor,
                                 AccessLogger accessLogger) {
        this.engine = engine;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    @Override
    public PolicyView policy() {
        requireView();
        return engine.policyView();
    }

    @Override
    public PositionView position() {
        requireView();
        return engine.position(true);
    }

    @Override
    public PageResponse<StatementView> statements(YearMonth month, PageQuery page) {
        requireView();
        return engine.statements(month, page);
    }

    @Override
    public StatementView statement(UUID statementId) {
        requireView();
        return engine.statement(statementId);
    }

    @Override
    public void exportStatement(UUID statementId, OutputStream output) {
        requireView();
        StatementView s = engine.statement(statementId);
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(), Map.of(
                "resource", RESOURCE, "resourceId", statementId.toString(), "usageHash", s.usageHash())));
        engine.export(statementId, output);
    }

    private void requireView() {
        accessScope.requirePermission(Permission.TENANT_BILLING_VIEW);
        accessScope.requireAllCreditors(RESOURCE);
    }
}
