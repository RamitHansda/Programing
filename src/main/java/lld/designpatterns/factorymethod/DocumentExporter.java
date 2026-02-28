package lld.designpatterns.factorymethod;

import java.util.Objects;

/**
 * Factory Method: document export. Callers use this interface; concrete exporters
 * are created by format-specific factories.
 */
public interface DocumentExporter {

    /**
     * Exports the given document and returns the result (e.g. file path or bytes).
     */
    ExportResult export(Document doc);

    enum ExportFormat {
        PDF, WORD, MARKDOWN
    }

    record Document(String title, String content, String author) {
        public Document {
            Objects.requireNonNull(title);
            Objects.requireNonNull(content);
        }
    }

    record ExportResult(ExportFormat format, String pathOrId, boolean success, String errorMessage) {
        public static ExportResult ok(ExportFormat format, String pathOrId) {
            return new ExportResult(format, pathOrId, true, null);
        }
        public static ExportResult fail(ExportFormat format, String errorMessage) {
            return new ExportResult(format, null, false, errorMessage);
        }
    }
}
