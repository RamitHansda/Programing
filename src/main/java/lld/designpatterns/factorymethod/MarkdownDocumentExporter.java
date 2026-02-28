package lld.designpatterns.factorymethod;

/**
 * Markdown export: minimal header, content as-is or with MD formatting.
 */
public final class MarkdownDocumentExporter implements DocumentExporter {

    @Override
    public ExportResult export(Document doc) {
        String header = "# " + doc.title() + "\n*By " + doc.author() + "*\n\n";
        String path = writeToFile((header + doc.content()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                doc.title(), ".md");
        return ExportResult.ok(ExportFormat.MARKDOWN, path);
    }

    private String writeToFile(byte[] data, String baseName, String ext) {
        String path = "/tmp/export/" + baseName.replaceAll("[^a-zA-Z0-9]", "_") + ext;
        return path;
    }
}
