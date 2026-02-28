package lld.designpatterns.proxy;

import java.util.List;
import java.util.Objects;

/**
 * Proxy: same interface as Report. Real report is loaded on first method call (lazy).
 * Optionally add access control per user.
 */
public final class LazyReportProxy implements Report {

    private final String reportId;
    private final ReportLoader loader;
    private final String allowedUserId;  // optional access control
    private volatile Report realReport;

    public LazyReportProxy(String reportId, ReportLoader loader) {
        this(reportId, loader, null);
    }

    public LazyReportProxy(String reportId, ReportLoader loader, String allowedUserId) {
        this.reportId = Objects.requireNonNull(reportId);
        this.loader = Objects.requireNonNull(loader);
        this.allowedUserId = allowedUserId;
    }

    private Report getRealReport(String callerUserId) {
        if (allowedUserId != null && !allowedUserId.equals(callerUserId)) {
            throw new SecurityException("Access denied for user: " + callerUserId);
        }
        if (realReport == null) {
            synchronized (this) {
                if (realReport == null) {
                    realReport = loader.load(reportId);
                    if (realReport == null) {
                        throw new IllegalStateException("Report not found: " + reportId);
                    }
                }
            }
        }
        return realReport;
    }

    /** Callers can pass null for userId if access control is not used. */
    public String getTitle(String callerUserId) {
        return getRealReport(callerUserId).getTitle();
    }

    @Override
    public String getTitle() {
        return getRealReport(null).getTitle();
    }

    @Override
    public List<String> getSections() {
        return getRealReport(null).getSections();
    }

    @Override
    public byte[] getRawContent() {
        return getRealReport(null).getRawContent();
    }

    @Override
    public String getReportId() {
        return reportId;
    }
}
