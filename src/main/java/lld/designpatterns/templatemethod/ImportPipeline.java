package lld.designpatterns.templatemethod;

import java.util.List;
import java.util.Objects;

/**
 * Template Method: skeleton of import (validate file → parse → transform → validate data → persist).
 * Subclasses implement the variable steps per file type (CSV, JSON, XML).
 */
public abstract class ImportPipeline<T> {

    public final ImportResult run(String filePath) {
        if (!validateFile(filePath)) {
            return ImportResult.fail("Invalid file: " + filePath);
        }
        List<RawRecord> raw = parse(filePath);
        if (raw == null || raw.isEmpty()) {
            return ImportResult.fail("Parse produced no records");
        }
        List<T> transformed = transform(raw);
        if (!validateData(transformed)) {
            return ImportResult.fail("Data validation failed");
        }
        persist(transformed);
        return ImportResult.ok(transformed.size());
    }

    /** Step 1: validate file exists and format. */
    protected boolean validateFile(String filePath) {
        return filePath != null && !filePath.isBlank();
    }

    /** Step 2: parse file into raw records. Implement per format. */
    protected abstract List<RawRecord> parse(String filePath);

    /** Step 3: transform to domain objects. */
    protected abstract List<T> transform(List<RawRecord> raw);

    /** Step 4: validate transformed data. */
    protected boolean validateData(List<T> data) {
        return data != null && !data.contains(null);
    }

    /** Step 5: persist. */
    protected abstract void persist(List<T> data);

    public record RawRecord(List<String> fields) {}
    public record ImportResult(boolean success, int recordCount, String errorMessage) {
        static ImportResult ok(int n) { return new ImportResult(true, n, null); }
        static ImportResult fail(String msg) { return new ImportResult(false, 0, msg); }
    }
}
