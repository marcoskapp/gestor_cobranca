package br.com.gestorcobranca.debt;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentCommand;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evidência da correção de valor enviada ao armazenamento sem transação aberta (decisão 872 revista pelo responsável),
 * contra PostgreSQL real: o envio acontece fora de qualquer transação (não prende conexão nem o título); um pagamento
 * que trava o mesmo título durante o envio conclui na hora; o pedido considera o saldo já com o pagamento; e o arquivo
 * de um pedido recusado depois do envio (pela regra ou por falha no registro) é removido; com pedido já pendente, a
 * recusa vem antes do envio.
 */
@Import(TitleAmountEvidenceUploadIT.StorageProbeConfig.class)
class TitleAmountEvidenceUploadIT extends IntegrationTest {

    static final Queue<String> STORED = new ConcurrentLinkedQueue<>();
    static final Queue<String> DELETED = new ConcurrentLinkedQueue<>();
    static volatile Runnable duringStore;

    /** Sonda do armazenamento: registra gravações e remoções e executa o gancho do teste durante a gravação. */
    @TestConfiguration
    static class StorageProbeConfig {
        @Bean
        static BeanPostProcessor evidenceStorageProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof FileStorage storage)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(FileStorage.class.getClassLoader(), new Class<?>[]{FileStorage.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("store") && duringStore != null) {
                                    duringStore.run();
                                }
                                Object result;
                                try {
                                    result = method.invoke(storage, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (result instanceof FileStorage.StoredFile stored) {
                                    STORED.add(stored.key());
                                } else if (method.getName().equals("delete")) {
                                    DELETED.add((String) args[0]);
                                }
                                return result;
                            });
                }
            };
        }
    }

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    ServiceContractUseCases contracts;
    @Autowired
    PortfolioUseCases portfolios;
    @Autowired
    DebtIngestionPort ingestion;
    @Autowired
    DebtLedgerPort ledgerPort;
    @Autowired
    DebtTitleUseCases titles;
    @Autowired
    TitleAmountAdjustmentUseCases adjustments;

    UUID tenant;
    UUID creditor;
    UUID portfolio;
    UUID debtor;
    Actor gestor;

    @BeforeEach
    void setUp() {
        String slug = "adj-up-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditor = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null,
                null)).id());
        portfolio = system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditor, "CT-UP",
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditor, contract, "UP", "Carteira", null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
        debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord("529.982.247-25", "Devedor Teste",
                LocalDate.of(1985, 3, 15), List.of(), null)).id());
        gestor = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Gestora",
                EnumSet.of(Permission.DEBTOR_VIEW, Permission.TITLE_AMOUNT_ADJUST, Permission.TITLE_AMOUNT_APPROVE),
                CreditorScope.ALL);
        STORED.clear();
        DELETED.clear();
    }

    @AfterEach
    void clearHook() {
        duringStore = null;
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private static AdjustmentCommand principalTo(String value) {
        return new AdjustmentCommand(Map.of("PRINCIPAL", new BigDecimal(value)), "CALCULATION_ERROR",
                "Juros lançados em duplicidade no principal", null);
    }

    private static EvidenceUpload pdf() {
        byte[] content = "%PDF-1.4\n% evidencia de teste\n".getBytes(StandardCharsets.US_ASCII);
        return new EvidenceUpload("confirmacao.pdf", "application/pdf", content.length, new ByteArrayInputStream(content));
    }

    @Test
    void paymentOnTheSameTitleDuringTheUploadDoesNotWaitAndRefusedRequestRemovesTheFile() throws Exception {
        UUID t = system(() -> ingestion.upsertTitle(new TitleRecord(creditor, portfolio, debtor, "T-UP", null, 1,
                LocalDate.of(2026, 3, 10), new BigDecimal("2000.00"), null, null), Ids.newId())).id();
        ExecutorService ledger = Executors.newSingleThreadExecutor();
        AtomicReference<Boolean> transactionDuringStore = new AtomicReference<>();
        try {
            duringStore = () -> {
                // o chamador do teste abre uma transação (callAs); o pedido a suspende durante o envio
                transactionDuringStore.set(TransactionSynchronizationManager.isActualTransactionActive());
                try {
                    ledger.submit(() -> system(() -> {
                        ledgerPort.applyPayment(new PaymentApplication(t, Ids.newId(),
                                ComponentAmounts.principalOnly(new BigDecimal("100.00")), Instant.now()));
                        return null;
                    })).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Pagamento no título esperou o envio da evidência", e);
                }
            };
            AdjustmentView pending = tenantExecution.callAs(gestor,
                    () -> adjustments.request(t, principalTo("1000.00"), pdf()));
            duringStore = null;
            assertThat(transactionDuringStore.get()).isFalse();
            assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
            // o saldo do pedido (lido com o título já travado) considera o pagamento que entrou durante o envio
            assertThat(pending.delta()).isEqualByComparingTo("-900.00");
            assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("1900.00");
            assertThat(STORED).hasSize(1);
            assertThat(DELETED).isEmpty();
        } finally {
            ledger.shutdownNow();
        }

        // já há um pedido pendente: recusado antes do envio, sem gravar arquivo
        assertThatThrownBy(() -> tenantExecution.callAs(gestor, () -> adjustments.request(t, principalTo("1500.00"),
                pdf()))).isInstanceOf(ConflictException.class).hasMessageContaining("aguardando aprovação");
        assertThat(STORED).hasSize(1);
        assertThat(DELETED).isEmpty();
        // o arquivo do pedido registrado continua lá
        assertThat(system(() -> adjustments.history(t)).adjustments()).singleElement()
                .satisfies(a -> assertThat(a.evidence()).isNotNull());
    }

    @Test
    void requestRefusedByTheRuleAfterTheUploadRemovesTheFile() {
        UUID t = system(() -> ingestion.upsertTitle(new TitleRecord(creditor, portfolio, debtor, "T-UP2", null, 1,
                LocalDate.of(2026, 3, 10), new BigDecimal("500.00"), null, null), Ids.newId())).id();
        // zerar o saldo não é correção: a regra recusa já com o arquivo gravado
        assertThatThrownBy(() -> tenantExecution.callAs(gestor, () -> adjustments.request(t, principalTo("0.00"),
                pdf()))).hasMessageContaining("cancele o título");
        assertThat(STORED).hasSize(1);
        assertThat(DELETED).containsExactlyElementsOf(STORED);
        assertThat(system(() -> adjustments.history(t)).adjustments()).isEmpty();
    }
}
