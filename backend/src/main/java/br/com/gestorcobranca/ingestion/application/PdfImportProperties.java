package br.com.gestorcobranca.ingestion.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Parâmetros da importação PDF inteligente ({@code app.ingestion.pdf.*}; desenho em
 * {@code docs/tecnico/desenho/importacao-pdf.md}).
 *
 * @param maxFileSize          tamanho máximo do PDF (padrão 20 MB)
 * @param maxPages             páginas aceitas em PDF com camada de texto (padrão 200)
 * @param maxVisualPages       páginas aceitas em PDF sem camada de texto, lidas visualmente (padrão 30)
 * @param visualPagesPerCall   páginas por chamada de leitura visual (padrão 5; o Bedrock aceita até 20 imagens)
 * @param visualDpi            resolução das imagens enviadas na leitura visual (padrão 150)
 * @param screenDpi            resolução das imagens da tela de validação (padrão 110)
 * @param minConfidence        confiança mínima da leitura visual sem confirmação individual (padrão 0,85)
 * @param promptMaxLines       linhas do texto posicionado enviadas ao modelo na proposta do mapa (padrão 220)
 * @param mapTimeout           prazo de uma chamada ao modelo (padrão 90 s)
 * @param mapMaxOutputTokens   tokens de saída da proposta do mapa (padrão 6.000)
 * @param visualMaxOutputTokens tokens de saída de uma chamada de leitura visual (padrão 12.000)
 * @param repairAttempts       novas tentativas quando a proposta da IA não passa na validação do mapa (padrão 1)
 * @param retention            tempo que o PDF fica guardado depois de aprovado, descartado ou com falha (padrão 120 dias)
 * @param retentionBatch       arquivos descartados por execução da retenção (padrão 200)
 */
@ConfigurationProperties(prefix = "app.ingestion.pdf")
public record PdfImportProperties(@DefaultValue("20MB") DataSize maxFileSize,
                                  @DefaultValue("200") int maxPages,
                                  @DefaultValue("30") int maxVisualPages,
                                  @DefaultValue("5") int visualPagesPerCall,
                                  @DefaultValue("150") float visualDpi,
                                  @DefaultValue("110") float screenDpi,
                                  @DefaultValue("0.85") double minConfidence,
                                  @DefaultValue("220") int promptMaxLines,
                                  @DefaultValue("PT90S") Duration mapTimeout,
                                  @DefaultValue("6000") int mapMaxOutputTokens,
                                  @DefaultValue("12000") int visualMaxOutputTokens,
                                  @DefaultValue("1") int repairAttempts,
                                  @DefaultValue("P120D") Duration retention,
                                  @DefaultValue("200") int retentionBatch) {

    public static PdfImportProperties defaults() {
        return new PdfImportProperties(DataSize.ofMegabytes(20), 200, 30, 5, 150, 110, 0.85, 220,
                Duration.ofSeconds(90), 6000, 12000, 1, Duration.ofDays(120), 200);
    }
}
