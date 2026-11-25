package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.creditor.CreditorPaymentUseCases.VerifiedEvidence;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.storage.MalwareScanner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Verificação do comprovante do portal do credor <b>antes</b> e <b>fora</b> da transação (decisão 1437, SEG-H06): lê o
 * arquivo (até o tamanho máximo), confere o tipo pelo conteúdo, recusa PDF com conteúdo ativo e passa pelo verificador
 * de malware. Infectado → 422 genérico e {@code MALWARE_DETECTED} (sem o nome do arquivo); verificador indisponível →
 * {@link MalwareScanner.MalwareScanUnavailableException} (o portal responde 503). Nada é gravado aqui: o mesmo conteúdo
 * verificado é o que o {@code EvidenceStore} grava, conferido pelo SHA-256.
 */
@Component
class CreditorEvidenceScreening {

    private static final java.util.Set<String> TYPES = java.util.Set.of("application/pdf", "image/png", "image/jpeg");

    private final MalwareScanner scanner;
    private final AccessLogger accessLogger;
    private final CurrentActor currentActor;
    private final PaymentMetrics metrics;
    private final PaymentProperties properties;
    private final Clock clock;

    CreditorEvidenceScreening(MalwareScanner scanner, AccessLogger accessLogger, CurrentActor currentActor,
                              PaymentMetrics metrics, PaymentProperties properties, Clock clock) {
        this.scanner = scanner;
        this.accessLogger = accessLogger;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    VerifiedEvidence screen(EvidenceUpload upload) {
        long max = properties.evidence().maxSizeBytes();
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "payment.evidence_required",
                "Anexe o comprovante do pagamento");
        Guard.require(upload.size() <= max, "payment.evidence_too_large", "O comprovante pode ter até 10 MB");
        byte[] content;
        try {
            content = upload.content().readNBytes((int) max + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o comprovante enviado", e);
        }
        Guard.require(content.length > 0, "payment.evidence_required", "Anexe o comprovante do pagamento");
        Guard.require(content.length <= max, "payment.evidence_too_large", "O comprovante pode ter até 10 MB");
        String contentType = upload.contentType() == null ? ""
                : upload.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        Guard.require(TYPES.contains(contentType), "payment.evidence_type_invalid", "O comprovante deve ser PDF, PNG ou JPEG");
        if (!EvidenceStore.signatureMatches(contentType, java.util.Arrays.copyOf(content, Math.min(content.length, 16)))) {
            throw new BusinessRuleException("payment.evidence_type_mismatch",
                    "O conteúdo do comprovante não corresponde ao tipo do arquivo");
        }
        if ("application/pdf".equals(contentType)) {
            EvidenceStore.withoutActiveContent(content);
        }
        MalwareScanner.ScanResult result;
        try {
            result = scanner.scan(new ByteArrayInputStream(content));
        } catch (MalwareScanner.MalwareScanUnavailableException e) {
            metrics.creditorEvidenceScan(scanner.engine(), "unavailable");
            throw e;
        }
        metrics.creditorEvidenceScan(scanner.engine(), result.verdict().name().toLowerCase(Locale.ROOT));
        if (result.verdict() == MalwareScanner.Verdict.INFECTED) {
            accessLogger.log(AccessEvent.of(AccessEventType.MALWARE_DETECTED, currentActor.get(),
                    Map.of("resource", "payment_evidence", "origin", "creditor_portal", "engine", scanner.engine())));
            throw new BusinessRuleException("payment.evidence_rejected",
                    "O comprovante foi recusado na verificação de segurança. Envie outro arquivo.");
        }
        return new VerifiedEvidence(upload.fileName(), contentType, content, sha256(content), result.verdict().name(),
                scanner.engine(), Instant.now(clock));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
