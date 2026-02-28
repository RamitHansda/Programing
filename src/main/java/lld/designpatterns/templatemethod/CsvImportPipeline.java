package lld.designpatterns.templatemethod;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CSV-specific steps: parse CSV lines, transform to domain type, persist.
 */
public final class CsvImportPipeline extends ImportPipeline<CsvImportPipeline.UserRecord> {

    @Override
    protected List<RawRecord> parse(String filePath) {
        // Simulated: in production read file and split by comma
        List<RawRecord> out = new ArrayList<>();
        out.add(new RawRecord(List.of("id", "name", "email")));
        out.add(new RawRecord(List.of("1", "Alice", "alice@example.com")));
        return out;
    }

    @Override
    protected List<UserRecord> transform(List<RawRecord> raw) {
        if (raw.size() < 2) return List.of();
        return raw.stream().skip(1)
                .map(r -> new UserRecord(
                        r.fields().size() > 0 ? r.fields().get(0) : "",
                        r.fields().size() > 1 ? r.fields().get(1) : "",
                        r.fields().size() > 2 ? r.fields().get(2) : ""))
                .collect(Collectors.toList());
    }

    @Override
    protected void persist(List<UserRecord> data) {
        // In production: insert into DB
    }

    public record UserRecord(String id, String name, String email) {}
}
