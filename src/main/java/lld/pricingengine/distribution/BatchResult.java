package lld.pricingengine.distribution;

import lld.pricingengine.model.PricingResult;

import java.util.Collections;
import java.util.List;

/**
 * The output from a single WorkBatch execution: all priced results plus diagnostics.
 */
public class BatchResult {
    private final int batchId;
    private final List<PricingResult> results;
    private final long processingTimeMs;

    public BatchResult(int batchId, List<PricingResult> results, long processingTimeMs) {
        this.batchId          = batchId;
        this.results          = Collections.unmodifiableList(results);
        this.processingTimeMs = processingTimeMs;
    }

    public int getBatchId()                    { return batchId; }
    public List<PricingResult> getResults()    { return results; }
    public long getProcessingTimeMs()          { return processingTimeMs; }

    public long successCount() {
        return results.stream().filter(PricingResult::isSuccess).count();
    }

    public long failureCount() {
        return results.stream().filter(r -> !r.isSuccess()).count();
    }
}
