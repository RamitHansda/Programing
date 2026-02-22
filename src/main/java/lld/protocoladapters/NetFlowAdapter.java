package lld.protocoladapters;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Adapter for NetFlow v9 / IPFIX. In production this would decode real
 * NetFlow templates and data records; here we simulate a single record
 * from a minimal "packet" for illustration.
 */
public class NetFlowAdapter implements ProtocolAdapter {

    @Override
    public String getProtocolName() {
        return "NetFlow";
    }

    @Override
    public List<FlowRecord> parse(byte[] raw) {
        if (raw == null || raw.length < 1) {
            return Collections.emptyList();
        }
        // Simulated: in real impl, decode NetFlow header + template + data
        // and map fields (src/dst IP/port, protocol, octets, packets, timestamps) to FlowRecord
        FlowRecord record = new FlowRecord(
                "10.0.0.1",
                "10.0.0.2",
                443,
                50000,
                "TCP",
                1500,
                10,
                Instant.now().minusSeconds(60),
                Instant.now(),
                "router-1",
                "exporter-1"
        );
        return List.of(record);
    }
}
