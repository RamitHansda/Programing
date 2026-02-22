package lld.protocoladapters;

import java.util.ArrayList;
import java.util.List;

/**
 * Ingest pipeline: accepts raw bytes and an adapter, normalizes to FlowRecords,
 * and "publishes" them (here we just collect; in production this would send to
 * Kafka with partition key e.g. hash(device_id, exporter_id)).
 *
 * Reasoning: pipeline is protocol-agnostic; it only knows about ProtocolAdapter
 * and FlowRecord. Adding a new protocol = add new adapter, no change here.
 */
public class IngestPipeline {

    private final ProtocolAdapter adapter;
    private final List<FlowRecord> emitted; // in prod: Kafka producer, etc.

    public IngestPipeline(ProtocolAdapter adapter) {
        this.adapter = adapter;
        this.emitted = new ArrayList<>();
    }

    public void ingest(byte[] raw) {
        List<FlowRecord> records = adapter.parse(raw);
        for (FlowRecord r : records) {
            emitted.add(r);
            // In production: producer.send(partitionKey(r), serialize(r));
        }
    }

    public List<FlowRecord> getEmitted() {
        return new ArrayList<>(emitted);
    }
}
