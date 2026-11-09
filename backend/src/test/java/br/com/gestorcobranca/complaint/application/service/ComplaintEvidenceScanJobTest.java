package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.model.ComplaintChannel;
import br.com.gestorcobranca.complaint.domain.model.ComplaintEvidence;
import br.com.gestorcobranca.complaint.domain.model.ComplaintSubject;
import br.com.gestorcobranca.complaint.domain.model.DeadlineSource;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintEvidenceRepository;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintRepository;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import br.com.gestorcobranca.shared.storage.MalwareScanner;
import br.com.gestorcobranca.shared.storage.MalwareScanner.MalwareScanUnavailableException;
import br.com.gestorcobranca.shared.storage.MalwareScanner.ScanResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verificação de malware das evidências de reclamação: infectada, sem veredito e conteúdo alterado na quarentena. */
class ComplaintEvidenceScanJobTest {

    private final ComplaintEvidenceRepository evidences = mock(ComplaintEvidenceRepository.class);
    private final ComplaintRepository complaints = mock(ComplaintRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final MalwareScanner scanner = mock(MalwareScanner.class);
    private final AccessLogger accessLogger = mock(AccessLogger.class);
    private final ComplaintEvidenceScanJob job = new ComplaintEvidenceScanJob(evidences, complaints, storage, scanner,
            new ComplaintFileCleanup(storage), mock(AuditTrail.class), accessLogger, mock(BusinessEventLogger.class),
            new ComplaintMetrics(new SimpleMeterRegistry()), Clock.systemUTC());

    private Complaint complaint;
    private ComplaintEvidence evidence;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization();
        complaint = new Complaint(UUID.randomUUID(), null, null, ComplaintChannel.PROCON, null, ComplaintSubject.OTHER,
                "Relato fictício", Instant.now(), LocalDate.now().plusDays(10), DeadlineSource.DEFAULT, 3, null);
        evidence = new ComplaintEvidence(complaint, "q/key", "n.pdf", "application/pdf", 10, "a".repeat(64));
        org.springframework.test.util.ReflectionTestUtils.setField(evidence, "createdAt", Instant.now());
        when(evidences.findById(evidence.getId())).thenReturn(Optional.of(evidence));
        when(complaints.findById(complaint.getId())).thenReturn(Optional.of(complaint));
        when(storage.open("q/key")).thenAnswer(i -> new ByteArrayInputStream(new byte[10]));
        when(scanner.engine()).thenReturn("clamav");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    private JobContext ctx(int attempt) {
        return new JobContext(UUID.randomUUID(), UUID.randomUUID(), ComplaintEvidenceScanJob.TYPE, attempt);
    }

    @Test
    void infectadaEhRecusadaSemArquivoEComLogDeMalware() {
        when(scanner.scan(any())).thenReturn(new ScanResult(MalwareScanner.Verdict.INFECTED, "Eicar-Test"));
        job.handle(ctx(1), new ComplaintEvidenceScanJob.Payload(evidence.getId()));
        assertThat(evidence.getStatus()).isEqualTo(ComplaintEvidence.Status.REJECTED);
        assertThat(evidence.getFileKey()).isNull();
        ArgumentCaptor<AccessEvent> logged = ArgumentCaptor.forClass(AccessEvent.class);
        verify(accessLogger).log(logged.capture());
        assertThat(logged.getValue().type()).isEqualTo(AccessEventType.MALWARE_DETECTED);
        verify(storage, never()).store(eq(FileCategory.EVIDENCE), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void verificadorIndisponivelNaUltimaTentativaDeixaSemVeredito() {
        when(scanner.scan(any())).thenThrow(new MalwareScanUnavailableException("fora do ar", null));
        assertThatThrownBy(() -> job.handle(ctx(1), new ComplaintEvidenceScanJob.Payload(evidence.getId())))
                .isInstanceOf(MalwareScanUnavailableException.class);
        assertThat(evidence.getStatus()).isEqualTo(ComplaintEvidence.Status.PENDING_SCAN);
        job.handle(ctx(ComplaintEvidenceScanJob.MAX_ATTEMPTS), new ComplaintEvidenceScanJob.Payload(evidence.getId()));
        assertThat(evidence.getStatus()).isEqualTo(ComplaintEvidence.Status.FAILED);
        verify(storage, never()).store(eq(FileCategory.EVIDENCE), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void conteudoAlteradoNaQuarentenaNaoEhLiberado() {
        when(scanner.scan(any())).thenReturn(new ScanResult(MalwareScanner.Verdict.CLEAN, null));
        when(storage.store(eq(FileCategory.EVIDENCE), anyString(), anyString(), any(), anyLong()))
                .thenReturn(new StoredFile(UUID.randomUUID(), "e/key", "n.pdf", "application/pdf", 10, "b".repeat(64)));
        assertThatThrownBy(() -> job.handle(ctx(1), new ComplaintEvidenceScanJob.Payload(evidence.getId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(evidence.getStatus()).isEqualTo(ComplaintEvidence.Status.PENDING_SCAN);
    }

    @Test
    void limpaEhLiberadaComOArquivoDeEvidencia() {
        when(scanner.scan(any())).thenReturn(new ScanResult(MalwareScanner.Verdict.CLEAN, null));
        when(storage.store(eq(FileCategory.EVIDENCE), anyString(), anyString(), any(), anyLong()))
                .thenReturn(new StoredFile(UUID.randomUUID(), "e/key", "n.pdf", "application/pdf", 10, "a".repeat(64)));
        job.handle(ctx(1), new ComplaintEvidenceScanJob.Payload(evidence.getId()));
        assertThat(evidence.getStatus()).isEqualTo(ComplaintEvidence.Status.AVAILABLE);
        assertThat(evidence.getFileKey()).isEqualTo("e/key");
        assertThat(evidence.getVerdict()).isEqualTo(ComplaintEvidence.Verdict.CLEAN);
    }
}
