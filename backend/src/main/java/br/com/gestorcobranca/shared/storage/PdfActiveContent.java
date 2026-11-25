package br.com.gestorcobranca.shared.storage;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Conteúdo ativo em PDF recebido de fora (importação PDF, comprovante de pagamento, documentos de contestação): o
 * PDFBox não executa JavaScript, mas o arquivo pode ser aberto depois num leitor que executa; a recusa evita guardar e
 * mostrar conteúdo ativo (RS-39 do portal do credor).
 */
public final class PdfActiveContent {

    private PdfActiveContent() {
    }

    /** Resultado da inspeção de um PDF recebido. */
    public enum Verdict { SAFE, ACTIVE_CONTENT, UNREADABLE }

    /** Teto de memória da leitura (o arquivo inteiro já chega limitado pelo tamanho máximo de quem chama). */
    private static final long MAX_PARSE_MEMORY = 48L * 1024 * 1024;

    /** Ações que executam algo ao abrir ou ao clicar (além do JavaScript). */
    private static final Set<COSName> ACTIVE_ACTIONS = Set.of(COSName.JAVA_SCRIPT, COSName.getPDFName("Launch"),
            COSName.getPDFName("SubmitForm"), COSName.getPDFName("ImportData"), COSName.getPDFName("RichMediaExecute"));
    /** Chaves de conteúdo ativo ou escondido: JavaScript, formulário XFA, arquivos embutidos e mídia interativa. */
    private static final Set<COSName> ACTIVE_KEYS = Set.of(COSName.JS, COSName.getPDFName("XFA"),
            COSName.EMBEDDED_FILES, COSName.getPDFName("RichMediaContent"), COSName.getPDFName("RichMediaSettings"));

    /**
     * Abre o PDF (sem senha, com teto de memória) e procura conteúdo ativo (JavaScript, ação de abrir programa, envio ou
     * importação de formulário, XFA, arquivos embutidos, mídia interativa). PDF protegido por senha, corrompido ou que o
     * PDFBox não lê é {@code UNREADABLE}: quem chama recusa, porque não dá para conferir o conteúdo.
     */
    public static Verdict inspect(byte[] content) {
        // no máximo algumas leituras ao mesmo tempo por instância: cada uma pode usar até o teto de memória (AQ-04)
        try {
            PARSES.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.UNREADABLE;
        }
        try {
            return parse(content);
        } finally {
            PARSES.release();
        }
    }

    /** Leituras simultâneas por instância (o restante espera, sem recusar). */
    static final int MAX_CONCURRENT_PARSES = 4;
    private static final java.util.concurrent.Semaphore PARSES = new java.util.concurrent.Semaphore(MAX_CONCURRENT_PARSES, true);

    private static Verdict parse(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content, "", null, null,
                org.apache.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(MAX_PARSE_MEMORY).streamCache)) {
            return hasActiveContent(document) ? Verdict.ACTIVE_CONTENT : Verdict.SAFE;
        } catch (IOException | RuntimeException e) {
            return Verdict.UNREADABLE;
        }
    }

    /** Conteúdo ativo em qualquer dicionário do documento (ações, chaves e arquivos embutidos). */
    public static boolean hasActiveContent(PDDocument document) throws IOException {
        return scan(document, true);
    }

    /** Procura {@code /JS} ou {@code /S /JavaScript} em qualquer dicionário do documento. */
    public static boolean hasJavaScript(PDDocument document) throws IOException {
        return scan(document, false);
    }

    private static boolean scan(PDDocument document, boolean anyActiveContent) throws IOException {
        if (document.getDocumentCatalog().getNames() != null
                && document.getDocumentCatalog().getNames().getJavaScript() != null) {
            return true;
        }
        Deque<COSBase> pending = new ArrayDeque<>();
        Set<COSBase> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(document.getDocumentCatalog().getCOSObject());
        for (COSObjectKey key : document.getDocument().getXrefTable().keySet()) {
            COSObject object = document.getDocument().getObjectFromPool(key);
            if (object != null) {
                pending.add(object);
            }
        }
        while (!pending.isEmpty()) {
            COSBase base = pending.pop();
            if (base instanceof COSObject indirect) {
                base = indirect.getObject();
            }
            if (base == null || !seen.add(base)) {
                continue;
            }
            if (base instanceof COSDictionary dict) {
                if (dict.containsKey(COSName.JS) || COSName.JAVA_SCRIPT.equals(dict.getCOSName(COSName.S))) {
                    return true;
                }
                COSName action = dict.getCOSName(COSName.S);
                if (anyActiveContent && ((action != null && ACTIVE_ACTIONS.contains(action))
                        || ACTIVE_KEYS.stream().anyMatch(dict::containsKey)
                        || COSName.EMBEDDED_FILE.equals(dict.getCOSName(COSName.TYPE)))) {
                    return true;
                }
                for (Map.Entry<COSName, COSBase> e : dict.entrySet()) {
                    // filhos indiretos já estão na fila pela tabela de referências; diretos são percorridos aqui
                    if (!(e.getValue() instanceof COSObject)) {
                        pending.push(e.getValue());
                    }
                }
            } else if (base instanceof COSArray array) {
                for (COSBase item : array) {
                    if (!(item instanceof COSObject)) {
                        pending.push(item);
                    }
                }
            }
        }
        return false;
    }
}
