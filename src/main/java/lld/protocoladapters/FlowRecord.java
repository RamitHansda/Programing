package lld.protocoladapters;

import java.time.Instant;

/**
 * Canonical flow record — the single internal representation all protocol
 * adapters produce. Downstream (bus, stream processing, storage) depends only
 * on this type.
 */
public class FlowRecord {

    private final String srcIp;
    private final String dstIp;
    private final int srcPort;
    private final int dstPort;
    private final String protocol;
    private final long bytes;
    private final long packets;
    private final Instant startTime;
    private final Instant endTime;
    private final String deviceId;
    private final String exporterId;

    public FlowRecord(String srcIp, String dstIp, int srcPort, int dstPort,
                      String protocol, long bytes, long packets,
                      Instant startTime, Instant endTime,
                      String deviceId, String exporterId) {
        this.srcIp = srcIp;
        this.dstIp = dstIp;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
        this.protocol = protocol;
        this.bytes = bytes;
        this.packets = packets;
        this.startTime = startTime;
        this.endTime = endTime;
        this.deviceId = deviceId;
        this.exporterId = exporterId;
    }

    public String getSrcIp() { return srcIp; }
    public String getDstIp() { return dstIp; }
    public int getSrcPort() { return srcPort; }
    public int getDstPort() { return dstPort; }
    public String getProtocol() { return protocol; }
    public long getBytes() { return bytes; }
    public long getPackets() { return packets; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public String getDeviceId() { return deviceId; }
    public String getExporterId() { return exporterId; }

    @Override
    public String toString() {
        return String.format("Flow[%s:%d -> %s:%d proto=%s bytes=%d device=%s]",
                srcIp, srcPort, dstIp, dstPort, protocol, bytes, deviceId);
    }
}
