package lld.pricingengine.distribution;

import lld.pricingengine.model.PricingRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a flat list of PricingRequests into fixed-size batches for distribution
 * across worker nodes. Round-robin partitioning ensures even load distribution.
 */
public class WorkPartitioner {

    private final int batchSize;

    public WorkPartitioner(int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("Batch size must be positive");
        this.batchSize = batchSize;
    }

    public List<WorkBatch> partition(List<PricingRequest> requests) {
        List<WorkBatch> batches = new ArrayList<>();
        int batchId = 0;

        for (int i = 0; i < requests.size(); i += batchSize) {
            int end = Math.min(i + batchSize, requests.size());
            batches.add(new WorkBatch(batchId++, new ArrayList<>(requests.subList(i, end))));
        }

        return batches;
    }
}
