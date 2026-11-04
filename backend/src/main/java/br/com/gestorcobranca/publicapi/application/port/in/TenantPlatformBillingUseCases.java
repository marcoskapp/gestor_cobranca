package br.com.gestorcobranca.publicapi.application.port.in;

import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.StatementView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.OutputStream;
import java.time.YearMonth;
import java.util.UUID;

/**
 * A cobrança da plataforma vista pela própria assessoria, somente leitura (transparência): a política que a plataforma
 * aplica, a posição do ciclo em andamento e os extratos fechados, com o mesmo cálculo da plataforma. Exige
 * {@code TENANT_BILLING_VIEW} e acesso a todos os credores (o extrato soma o consumo de todos eles).
 */
public interface TenantPlatformBillingUseCases {

    PolicyView policy();

    PositionView position();

    PageResponse<StatementView> statements(YearMonth month, PageQuery page);

    StatementView statement(UUID statementId);

    /** Extrato em CSV; registra {@code DATA_EXPORTED}. */
    void exportStatement(UUID statementId, OutputStream output);
}
