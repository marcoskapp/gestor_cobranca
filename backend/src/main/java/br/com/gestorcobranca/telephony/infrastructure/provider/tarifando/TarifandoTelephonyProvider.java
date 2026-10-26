package br.com.gestorcobranca.telephony.infrastructure.provider.tarifando;

import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Esqueleto <b>desligado</b> do adaptador da Tarifando Telecom (PABX IP e Call Center em nuvem,
 * Palhoça/SC). O fornecedor anuncia "APIs e integração via REST" e click to call nos planos de PABX
 * e Call Center, mas não publica documentação: o que falta obter está no anexo B de
 * {@code docs/tecnico/desenho/telefonia.md}.
 * <p>
 * {@link Readiness#NOT_IMPLEMENTED}: a aplicação recusa criar e ativar conta deste provedor e o
 * {@code ProviderResolver} nunca o usa para discar; as operações abaixo recusam explicitamente
 * (nada é simulado). Ao receber a documentação: implementar como o adaptador da Alvaria
 * ({@code RestClient}, timeouts 3 s/10 s, {@code integration.calls{provider=tarifando,…}},
 * 429/5xx/rede → {@code TransientProviderException}), o {@code TelephonyWebhookAdapter} do
 * provedor, os testes com servidor HTTP local e só então mudar {@link #readiness()}.
 */
@Component
class TarifandoTelephonyProvider implements TelephonyProvider {

    @Override
    public ProviderType provider() {
        return ProviderType.TARIFANDO;
    }

    @Override
    public Readiness readiness() {
        return Readiness.NOT_IMPLEMENTED;
    }

    @Override
    public DialResult originate(OriginateRequest request) {
        throw notImplemented();
    }

    @Override
    public List<DialResult> dialBatch(ProviderContext context, List<DialRequest> requests) {
        throw notImplemented();
    }

    @Override
    public void bridge(BridgeRequest request) {
        throw notImplemented();
    }

    @Override
    public void hangup(HangupRequest request) {
        throw notImplemented();
    }

    @Override
    public RecordingMedia fetchRecording(ProviderContext context, String recordingRef) {
        throw notImplemented();
    }

    @Override
    public void deleteRecording(ProviderContext context, String recordingRef) {
        throw notImplemented();
    }

    private static IllegalStateException notImplemented() {
        return new IllegalStateException("Adaptador da Tarifando não implementado (aguarda a documentação da API)");
    }
}
