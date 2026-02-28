package lld.designpatterns.factorymethod;

/**
 * Word export: uses Word-specific headers and encoding.
 */
public final class WordDocumentExporter implements DocumentExporter {

    @Override
    public ExportResult export(Document doc) {
        String header = buildWordHeader(doc);
        byte[] encoded = encodeForWord(header, doc.content());
        String path = writeToFile(encoded, doc.title(), ".docx");
        return ExportResult.ok(ExportFormat.WORD, path);
    }

    private String buildWordHeader(Document doc) {
        return "[Word XML Header] Title: " + doc.title() + ", Author: " + doc.author();
    }

    private byte[] encodeForWord(String header, String content) {
        return (header + "\n" + content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String writeToFile(byte[] data, String baseName, String ext) {
        String path = "/tmp/export/" + baseName.replaceAll("[^a-zA-Z0-9]", "_") + ext;
        return path;
    }
}
