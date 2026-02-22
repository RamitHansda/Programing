package lld.protocoladapters;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Adapter for sFlow. In production this would decode sFlow datagram header
 * and sample records; here we simulate one flow from "sFlow" input.
 */
public class SFlowAdapter implements ProtocolAdapter {

    @Override
    public String getProtocolName() {
        return "sFlow";
    }

    @Override
    public List<FlowRecord> parse(byte[] raw) {
        if (raw == null || raw.length < 1) {
            return Collections.emptyList();
        }
        // Simulated: real impl would parse sFlow packet and map to FlowRecord
        FlowRecord record = new FlowRecord(
                "192.168.1.10",
                "8.8.8.8",
                52000,
                53,
                "UDP",
                128,
                1,
                Instant.now().minusSeconds(5),
                Instant.now(),
                "switch-1",
                "sflow-agent-1"
        );
        return List.of(record);
    }
}
