/**
 * Pagamentos (M07 parcial, F10, F14, F15, RB06) — ver docs/tecnico/desenho/pagamentos.md.
 * <p>
 * Contas Pix por assessoria/credor (API Pix do BACEN, OAuth2 + mTLS, segredos e certificado
 * criptografados), instruções de pagamento (Pix imediato/com vencimento; portas de boleto e link),
 * pagamentos integrados (webhook Pix confirmado por consulta ao PSP, arquivo de retorno) e externos
 * (evidência obrigatória, revisão por segunda pessoa acima do limite de risco), alocação em parcelas de
 * acordo e títulos avulsos, realocação de sobras e de valores não aplicados, estornos compensatórios e
 * conciliação com fila de divergências.
 * <p>
 * Dependências: {@code negotiation} (acordos e parcelas — leitura), {@code debt} (títulos, devedor para
 * o Pix com vencimento e razão do título para pagamentos avulsos) e {@code organization} (credores).
 * Publica {@code shared.events.payment.PaymentConfirmed}/{@code PaymentReversed} (uma vez por alocação);
 * consome {@code shared.events.negotiation.AgreementPaymentRecorded}, {@code AgreementStatusChanged},
 * {@code InstallmentStatusChanged} e {@code shared.events.debt.TitleStatusChanged}.
 * <p>
 * Implementa a porta {@code negotiation :: spi} {@code PaymentReviewHolds} (ADR-0010): a quebra do acordo
 * fica suspensa enquanto houver pagamento externo do acordo em revisão; ao concluir uma revisão, chama
 * {@code AgreementBreakSuspensionUseCases#reconcile} na mesma transação.
 */
@ApplicationModule(displayName = "Payment",
        allowedDependencies = {"shared", "organization :: usecases", "debt :: usecases", "negotiation :: usecases",
                "negotiation :: spi"})
package br.com.gestorcobranca.payment;

import org.springframework.modulith.ApplicationModule;
