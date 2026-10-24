/**
 * Comissão da assessoria junto ao credor e prestação de contas (F17, F22, RB08 —
 * docs/tecnico/desenho/prestacao-de-contas.md): regras de comissionamento versionadas por contrato de
 * serviço, recebimentos e razão de comissão append-only alimentados por eventos de pagamento,
 * demonstrativo por credor com ciclo segregado de aprovação, exportação conciliável e repasses.
 * <p>
 * Distinto do {@code incentive}: aqui se calcula quanto a <em>assessoria</em> recebe do <em>credor</em>;
 * lá, quanto o <em>negociador</em> recebe da assessoria. A base de ambos é a recuperação confirmada.
 * <p>
 * Dependências: {@code payment} (situação de conciliação e origem do pagamento), {@code negotiation}
 * (distribuição do pagamento no acordo), {@code organization} (contratos e carteiras). Custas do
 * jurídico chegam por evento ({@code LegalExpenseChanged}).
 */
@ApplicationModule(displayName = "Settlement",
        allowedDependencies = {"shared", "payment :: usecases", "negotiation :: usecases", "organization :: usecases"})
package br.com.gestorcobranca.settlement;

import org.springframework.modulith.ApplicationModule;
