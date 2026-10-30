package br.com.gestorcobranca.publicapi.application.port.in.gateway;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportRecordsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand;

/**
 * Importação pela API pública: delega ao módulo ingestion (mesma validação, conferência de totais, idempotência por
 * linha e processamento assíncrono) e vincula o lote ao token, para atribuir os registros processados ao consumo.
 * A chave {@code Idempotency-Key} é obrigatória e isolada por token (o mesmo valor em tokens diferentes não colide).
 */
public interface PublicImports {

    ImportBatchView importRecords(ImportRecordsCommand command, String idempotencyKey);

    ImportBatchView uploadFile(UploadFileCommand command, String idempotencyKey);

    /**
     * Valida a {@code Idempotency-Key} do cliente (8 a 80 caracteres: letras, dígitos, {@code . _ : -}) e a isola pelo
     * token, para os demais comandos repetíveis da API pública (ex.: pagamento informado). Ausente → nulo.
     */
    String scopedIdempotencyKey(String idempotencyKey, boolean required);
}
