package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderContext;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.RecordingMedia;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallRecording;
import br.com.gestorcobranca.telephony.domain.model.RecordingStatus;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.CallRecordingRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cópia da gravação do provedor para o armazenamento próprio ({@code telephony.recording_fetch}):
 * tipo de áudio conferido, tamanho limitado, SHA-256 calculado, arquivo na categoria
 * {@code CALL_RECORDING} (chave prefixada pelo tenant, SSE no S3). Depois da cópia, a gravação é
 * removida do provedor (melhor esforço: o dado pessoal fica num lugar só). Se a transação for
 * desfeita, o arquivo copiado é removido. Falha transitória → nova tentativa; tentativas esgotadas
 * → {@code FAILED} (alarme {@code telephony.jobs.failures}).
 */
@Component
class RecordingFetchJob implements JobHandler<RecordingFetchJob.Payload> {

    static final String TYPE = "telephony.recording_fetch";
    private static final Logger LOG = LoggerFactory.getLogger(RecordingFetchJob.class);
    private static final Set<String> AUDIO_TYPES = Set.of("audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3",
            "audio/ogg", "audio/opus", "audio/webm");

    private final CallRecordingRepository recordings;
    private final CallRepository calls;
    private final TelephonyAccountRepository accounts;
    private final ProviderResolver resolver;
    private final FileStorage storage;
    private final TenantExecution tenantExecution;
    private final BusinessEventLogger businessEvents;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    RecordingFetchJob(CallRecordingRepository recordings, CallRepository calls, TelephonyAccountRepository accounts,
                      ProviderResolver resolver, FileStorage storage, TenantExecution tenantExecution,
                      BusinessEventLogger businessEvents, TelephonyMetrics metrics, TelephonyProperties properties,
                      Clock clock) {
        this.recordings = recordings;
        this.calls = calls;
        this.accounts = accounts;
        this.resolver = resolver;
        this.storage = storage;
        this.tenantExecution = tenantExecution;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID recordingId) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return 6;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<CallRecording> found = recordings.findById(payload.recordingId());
        if (found.isEmpty() || found.get().getStatus() != RecordingStatus.PENDING) {
            return;
        }
        CallRecording recording = found.get();
        Call call = calls.findById(recording.getCallId()).orElseThrow();
        Optional<TelephonyAccount> account = accounts.findById(call.getAccountId());
        Optional<TelephonyProvider> provider = account.flatMap(a -> resolver.provider(a.getProvider()));
        if (provider.isEmpty()) {
            markFailed(recording, "ACCOUNT_UNAVAILABLE");
            return;
        }
        ProviderContext providerContext = resolver.context(account.get());
        StoredFile stored;
        try {
            RecordingMedia media = provider.get().fetchRecording(providerContext, recording.getProviderRecordingRef());
            String contentType = media.contentType() == null ? "" : media.contentType().toLowerCase(Locale.ROOT).trim();
            if (!AUDIO_TYPES.contains(contentType)) {
                close(media.content());
                markFailed(recording, "UNSUPPORTED_TYPE");
                return;
            }
            if (media.size() > properties.recordingMaxBytes()) {
                close(media.content());
                metrics.recording("too_large");
                markFailed(recording, "TOO_LARGE");
                return;
            }
            try (InputStream in = media.content()) {
                stored = storage.store(FileCategory.CALL_RECORDING, "call-" + call.getId() + extension(contentType),
                        contentType, in, media.size());
            }
        } catch (TransientProviderException e) {
            if (context.attempt() >= maxAttempts()) {
                metrics.jobFailure(TYPE);
                tenantExecution.inTenant(context.tenantId(), "telephony.recording_failed", () ->
                        recordings.findById(recording.getId()).ifPresent(r -> markFailed(r, "RETRIES_EXHAUSTED")));
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao copiar a gravação", e);
        }
        if (stored.size() > properties.recordingMaxBytes()) {
            storage.delete(stored.key());
            metrics.recording("too_large");
            markFailed(recording, "TOO_LARGE");
            return;
        }
        deleteOnRollback(stored.key());
        recording.stored(stored.key(), stored.contentType(), stored.size(), stored.sha256(), Instant.now(clock));
        recordings.save(recording);
        metrics.recording("stored");
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CALL_RECORDING_STORED)
                .creditor(call.getCreditorId()).portfolio(call.getPortfolioId()).debtor(call.getDebtorId())
                .collectionCase(call.getCaseId()).reference("call", call.getId()).channel("PHONE")
                .attr("recordingId", recording.getId()).attr("durationSeconds", recording.getDurationSeconds())
                .attr("announced", recording.isAnnounced()).attr("sizeBytes", stored.size()));
        try {
            provider.get().deleteRecording(providerContext, recording.getProviderRecordingRef());
        } catch (TransientProviderException e) {
            LOG.atWarn().addKeyValue("recordingId", recording.getId())
                    .log("Gravação copiada, mas não removida do provedor; remover pela retenção do provedor");
        }
    }

    private void markFailed(CallRecording recording, String code) {
        recording.failed(code);
        recordings.save(recording);
        metrics.recording("failed");
        if (!"RETRIES_EXHAUSTED".equals(code)) {
            LOG.atWarn().addKeyValue("recordingId", recording.getId()).addKeyValue("code", code)
                    .log("Gravação não copiada");
        }
    }

    private void deleteOnRollback(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        storage.delete(key);
                    }
                }
            });
        }
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/ogg", "audio/opus" -> ".ogg";
            case "audio/webm" -> ".webm";
            default -> ".wav";
        };
    }

    private static void close(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nada a fazer: o arquivo não foi usado
        }
    }
}
