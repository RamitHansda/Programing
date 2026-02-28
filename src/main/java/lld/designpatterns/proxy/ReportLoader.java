package lld.designpatterns.proxy;

/**
 * Loads a heavy report (e.g. from DB or network). Used by proxy for lazy loading.
 */
@FunctionalInterface
public interface ReportLoader {

    Report load(String reportId);
}
