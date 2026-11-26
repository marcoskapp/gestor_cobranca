package br.com.gestorcobranca.payment.application.port.in.creditor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Pagamento direto informado pela pessoa do credor no portal (ADR 0011, fatia 6). O credor vem sempre do ator
 * {@code CREDITOR}; a pessoa informa só os números dos próprios títulos — devedor, acordo e parcelas são derivados no
 * servidor (RS-34). Todo informe fica em análise até a revisão de outra pessoa da assessoria (RS-33), com a nova
 * verificação do MFA na mesma transação (comprovante de uso único, SEG-F07) e o comprovante já verificado (malware e
 * conteúdo ativo) antes da transação (RS-39).
 */
public interface CreditorPaymentUseCases {

    /** Título do próprio credor pelo número exato; inexistente, de outro credor ou que não aceita pagamento → vazio. */
    List<CreditorTitleMatch> findTitles(String number);

    /**
     * Verificação do comprovante <b>fora</b> de transação (tipo pelo conteúdo, tamanho, conteúdo ativo em PDF e
     * malware). Infectado → 422; verificador indisponível → 503. Nada é gravado.
     */
    VerifiedEvidence screen(EvidenceUpload upload);

    /**
     * Sem efeito, antes da nova verificação do MFA: permissão, destino derivável, limite de informes em análise. A
     * repetição com a mesma chave e o mesmo conteúdo passa (devolverá o informe já gravado).
     */
    void requireCanReport(ReportCommand command, VerifiedEvidence evidence, String idempotencyKey);

    /**
     * Grava o informe em análise. Exige a transação da ação e o comprovante da nova verificação emitido nela para
     * {@link #subjectRef}.
     *
     * @param idempotencyKey obrigatória (8 a 80 caracteres {@code [A-Za-z0-9._:-]}); escopo da pessoa do credor
     */
    ReportedPayment report(ReportCommand command, VerifiedEvidence evidence, String idempotencyKey, String stepUpProof);

    /** Informes feitos no portal pelo credor da sessão, do mais recente ao mais antigo. */
    List<ReportedPayment> reported();

    /**
     * Informes do portal recusados pela assessoria nos últimos {@value #REJECTED_WINDOW_DAYS} dias (pela data da
     * decisão), do mais recente ao mais antigo — o aviso "Confira e informe de novo" do "Aguardando você"
     * ({@code CREDITOR_PORTAL_OVERVIEW}). Mesmo filtro da lista: só os informes feitos no portal pelo credor da sessão.
     */
    List<ReportedPayment> recentlyRejected();

    int REJECTED_WINDOW_DAYS = 30;

    /**
     * @param titleNumbers números dos títulos no credor (até 20)
     * @param method       PIX, BOLETO, CARD, TRANSFER, CASH ou OTHER
     */
    record ReportCommand(List<String> titleNumbers, BigDecimal amount, LocalDate paidOn, String method,
                         String reference, String note) {
    }

    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    /**
     * Comprovante verificado, ainda não gravado.
     *
     * @param verdict CLEAN ou NOT_SCANNED (verificação desligada)
     */
    record VerifiedEvidence(String fileName, String contentType, byte[] content, String sha256, String verdict,
                            String engine, Instant scannedAt) {
    }

    /**
     * @param underAgreement o título está num acordo ativo: o pagamento vai para as parcelas em aberto do acordo
     */
    record CreditorTitleMatch(String number, Integer installmentNumber, LocalDate dueDate, BigDecimal openBalance,
                              boolean underAgreement) {
    }

    /**
     * @param status          IN_REVIEW, CONFIRMED (inclui conciliado), REJECTED ou REVERSED
     * @param rejectionReason motivo codificado para o credor (PROOF_INVALID, AMOUNT_OR_DATE_MISMATCH, WRONG_TITLES,
     *                        DUPLICATE ou OTHER); nulo fora da recusa
     */
    record ReportedPayment(UUID id, String status, BigDecimal amount, LocalDate paidOn, String method,
                           List<String> titleNumbers, String reference, Instant informedAt, Instant decidedAt,
                           String rejectionReason) {
    }

    /**
     * Alvo da nova verificação do MFA para o informe: o que a pessoa confirmou (títulos, valor, data e comprovante).
     * O portal e o payment calculam o mesmo valor; um comprovante emitido para outro informe não vale.
     */
    static String subjectRef(ReportCommand c, String evidenceSha256) {
        String canonical = String.join("|", "payment_report.v1",
                String.join(",", c.titleNumbers().stream().map(String::strip).sorted().toList()),
                c.amount() == null ? "-" : c.amount().stripTrailingZeros().toPlainString(),
                String.valueOf(c.paidOn()), String.valueOf(evidenceSha256));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
