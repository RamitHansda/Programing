package lld.designpatterns.proxy;

import java.util.List;
import java.util.Objects;

/**
 * Real subject: loaded from disk/network. Expensive to construct.
 */
public final class HeavyReport implements Report {

    private final String reportId;
    private final String title;
    private final List<String> sections;
    private final byte[] rawContent;

    public HeavyReport(String reportId, String title, List<String> sections, byte[] rawContent) {
        this.reportId = Objects.requireNonNull(reportId);
        this.title = Objects.requireNonNull(title);
        this.sections = sections != null ? List.copyOf(sections) : List.of();
        this.rawContent = rawContent != null ? rawContent : new byte[0];
    }

    @Override
    public String getTitle() { return title; }

    @Override
    public List<String> getSections() { return sections; }

    @Override
    public byte[] getRawContent() { return rawContent; }

    @Override
    public String getReportId() { return reportId; }
}
