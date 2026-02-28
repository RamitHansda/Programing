package lld.designpatterns.proxy;

import java.util.List;

/**
 * Heavy object: expensive to load. Proxy implements same interface and lazy-loads the real report.
 */
public interface Report {

    String getTitle();
    List<String> getSections();
    byte[] getRawContent();
    String getReportId();
}
