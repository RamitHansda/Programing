package lld.designpatterns.factorymethod;

/**
 * PDF export: adds PDF header, encodes content, writes to file.
 */
public final class PdfDocumentExporter implements DocumentExporter {

    @Override
    public ExportResult export(Document doc) {
        String header = buildPdfHeader(doc);
        byte[] encoded = encodeForPdf(header, doc.content());
        String path = writeToFile(encoded, doc.title(), ".pdf");
        return ExportResult.ok(ExportFormat.PDF, path);
    }

    private String buildPdfHeader(Document doc) {
        return "%PDF-1.4\n% " + doc.title() + "\n% Author: " + doc.author();
    }

    private byte[] encodeForPdf(String header, String content) {
        return (header + "\n\n" + content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String writeToFile(byte[] data, String baseName, String ext) {
        String path = "/tmp/export/" + baseName.replaceAll("[^a-zA-Z0-9]", "_") + ext;
        // In production: Files.write(Path.of(path), data);
        return path;
    }
}
