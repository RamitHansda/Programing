package lld.pricingengine.distribution;

import lld.pricingengine.model.PricingRequest;
import lld.pricingengine.model.PricingResult;
import lld.pricingengine.pricing.PricingModel;
import lld.pricingengine.pricing.PricingModelFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * A unit of work executed on a single thread (simulating a distributed node).
 * Prices every request in its assigned WorkBatch, isolating failures per instrument.
 */
public class PricingWorker implements Callable<BatchResult> {

    private static final Logger LOG = Logger.getLogger(PricingWorker.class.getName());

    private final WorkBatch batch;
    private final PricingModelFactory modelFactory;

    public PricingWorker(WorkBatch batch, PricingModelFactory modelFactory) {
        this.batch        = batch;
        this.modelFactory = modelFactory;
    }

    @Override
    public BatchResult call() {
        long start = System.currentTimeMillis();
        List<PricingResult> results = new ArrayList<>(batch.size());

        for (PricingRequest request : batch.getRequests()) {
            try {
                PricingModel model = modelFactory.resolve(request.getInstrument().getType());
                results.add(model.price(request));
            } catch (Exception e) {
                LOG.warning("Pricing failed for instrument " + request.getInstrument().getId()
                    + ": " + e.getMessage());
                results.add(PricingResult
                    .builder(request.getInstrument().getId(),
                             request.getInstrument().getTicker(),
                             request.getInstrument().getType())
                    .failed(e.getMessage())
                    .build());
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        return new BatchResult(batch.getBatchId(), results, elapsed);
    }
}
