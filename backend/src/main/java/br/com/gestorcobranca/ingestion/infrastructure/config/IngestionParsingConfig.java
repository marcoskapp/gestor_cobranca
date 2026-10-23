package br.com.gestorcobranca.ingestion.infrastructure.config;

import br.com.gestorcobranca.ingestion.application.IngestionProperties;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.context.annotation.Configuration;

/**
 * Limites de descompactação do Apache POI ({@code ZipSecureFile}), conferidos durante a leitura
 * real de cada parte do XLSX — complementam a inspeção do índice feita por {@code XlsxRecordReader},
 * que só enxerga os tamanhos declarados. São parâmetros globais da biblioteca (valem para toda
 * leitura de XLSX da aplicação) e mais restritivos que os padrões do POI.
 */
@Configuration(proxyBeanMethods = false)
class IngestionParsingConfig {

    IngestionParsingConfig(IngestionProperties properties) {
        ZipSecureFile.setMinInflateRatio(1.0d / properties.xlsxMaxCompressionRatio());
        ZipSecureFile.setMaxEntrySize(properties.xlsxMaxUncompressedSize().toBytes());
        ZipSecureFile.setMaxFileCount(properties.xlsxMaxEntries());
    }
}
