package lld.protocoladapters;

import java.util.List;

/**
 * Contract for protocol adapters: read protocol-specific input and produce
 * canonical FlowRecords. One implementation per protocol (NetFlow, sFlow, REST, etc.).
 */
public interface ProtocolAdapter {

    /**
     * Protocol name for logging and metrics (e.g. "NetFlow", "sFlow", "REST").
     */
    String getProtocolName();

    /**
     * Parse raw input into one or more canonical flow records.
     * Implementations should handle parse errors without bringing down the process
     * (log/metric and return empty list or skip bad record).
     *
     * @param raw protocol-specific payload (e.g. UDP packet bytes, JSON bytes)
     * @return list of normalized FlowRecords; empty if nothing to emit or on parse error
     */
    List<FlowRecord> parse(byte[] raw);
}
