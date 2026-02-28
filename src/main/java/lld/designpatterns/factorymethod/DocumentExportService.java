package lld.designpatterns.factorymethod;

import java.util.EnumMap;
import java.util.Map;

/**
 * Entry point: creates the right exporter per format (Factory Method).
 * New formats are added by registering a new exporter implementation.
 */
public final class DocumentExportService {

    private final Map<DocumentExporter.ExportFormat, DocumentExporter> exporters = new EnumMap<>(DocumentExporter.ExportFormat.class);

    public DocumentExportService() {
        register(DocumentExporter.ExportFormat.PDF, new PdfDocumentExporter());
        register(DocumentExporter.ExportFormat.WORD, new WordDocumentExporter());
        register(DocumentExporter.ExportFormat.MARKDOWN, new MarkdownDocumentExporter());
    }

    public void register(DocumentExporter.ExportFormat format, DocumentExporter exporter) {
        if (exporter == null) throw new IllegalArgumentException("exporter");
        exporters.put(format, exporter);
    }

    /**
     * Exports the document in the requested format. Returns result with path or error.
     */
    public DocumentExporter.ExportResult export(DocumentExporter.Document doc, DocumentExporter.ExportFormat format) {
        DocumentExporter exporter = exporters.get(format);
        if (exporter == null) {
            return DocumentExporter.ExportResult.fail(format, "Unsupported format: " + format);
        }
        return exporter.export(doc);
    }
}
