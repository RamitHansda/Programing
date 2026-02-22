package lld.protocoladapters;

import java.util.List;

/**
 * Demo: same ingest pipeline works with NetFlow or sFlow adapter; output
 * is always canonical FlowRecord. This shows why we build protocol adapters
 * — one contract, many protocols, one canonical type downstream.
 */
public class ProtocolAdapterDemo {

    public static void main(String[] args) {
        byte[] netflowPayload = new byte[] { 0x00, 0x09 }; // minimal mock
        byte[] sflowPayload = new byte[] { 0x00, 0x05 };

        // Pipeline with NetFlow adapter
        IngestPipeline netflowPipeline = new IngestPipeline(new NetFlowAdapter());
        netflowPipeline.ingest(netflowPayload);
        List<FlowRecord> fromNetFlow = netflowPipeline.getEmitted();

        // Pipeline with sFlow adapter
        IngestPipeline sflowPipeline = new IngestPipeline(new SFlowAdapter());
        sflowPipeline.ingest(sflowPayload);
        List<FlowRecord> fromSFlow = sflowPipeline.getEmitted();

        System.out.println("From NetFlow adapter: " + fromNetFlow);
        System.out.println("From sFlow adapter:   " + fromSFlow);
        System.out.println("Both produce FlowRecord — downstream stays protocol-agnostic.");
    }
}
