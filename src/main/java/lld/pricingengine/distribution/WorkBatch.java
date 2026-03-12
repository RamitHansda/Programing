package lld.pricingengine.distribution;

import lld.pricingengine.model.PricingRequest;

import java.util.Collections;
import java.util.List;

/**
 * An immutable slice of pricing work assigned to a single worker node.
 * Batching amortises scheduling overhead across many small instruments.
 */
public class WorkBatch {
    private final int batchId;
    private final List<PricingRequest> requests;

    public WorkBatch(int batchId, List<PricingRequest> requests) {
        this.batchId  = batchId;
        this.requests = Collections.unmodifiableList(requests);
    }

    public int getBatchId()                   { return batchId; }
    public List<PricingRequest> getRequests() { return requests; }
    public int size()                         { return requests.size(); }

    @Override
    public String toString() {
        return String.format("WorkBatch{id=%d, size=%d}", batchId, requests.size());
    }
}
