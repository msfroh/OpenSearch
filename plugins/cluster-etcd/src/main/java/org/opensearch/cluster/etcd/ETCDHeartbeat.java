package org.opensearch.cluster.etcd;

import io.etcd.jetcd.Client;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.monitor.fs.FsProbe;
import java.io.IOException;
import org.opensearch.monitor.fs.FsInfo;
import io.etcd.jetcd.ByteSequence;
import org.opensearch.env.NodeEnvironment;
import io.etcd.jetcd.KV;
import org.opensearch.monitor.os.OsProbe;
import org.opensearch.monitor.os.OsStats;
import org.opensearch.monitor.jvm.JvmStats;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentType;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

public class ETCDHeartbeat {
    private static final long HEARTBEAT_INTERVAL_SECONDS = 5;
    private final Logger logger = LogManager.getLogger(getClass());
    private final String nodeName;
    private final String nodeId;
    private final String ephemeralId;
    private final Client etcdClient;
    private final ScheduledExecutorService scheduler;
    private final ByteSequence nodeStateKey;
    private final NodeEnvironment nodeEnvironment;

    public ETCDHeartbeat(DiscoveryNode localNode, Client etcdClient, NodeEnvironment nodeEnvironment) {
        this.nodeName = localNode.getName();
        this.nodeId = localNode.getId();
        this.ephemeralId = localNode.getEphemeralId();
        this.etcdClient = etcdClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.nodeStateKey = ByteSequence.from("actual-state/node-state/" + nodeName, StandardCharsets.UTF_8);
        this.nodeEnvironment = nodeEnvironment;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::publishHeartbeat, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate in 5 seconds");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Scheduler interrupted", e);
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void publishHeartbeat() {
        // Get cpu info
        OsStats osStats = OsProbe.getInstance().osStats();
        int cpuPercent = osStats.getCpu().getPercent();

        // Get memory info
        int memoryPercent = osStats.getMem().getUsedPercent();
        ByteSizeValue memoryMax = osStats.getMem().getTotal();
        ByteSizeValue memoryUsed = osStats.getMem().getUsed();

       // Disk
        FsProbe fsProbe = new FsProbe(nodeEnvironment, null);
        long diskTotalMB = 0;
        long diskAvailableMB = 0;
        try {
            FsInfo fsInfo = fsProbe.stats(null);
            for (FsInfo.Path path : fsInfo) {
                diskTotalMB += path.getTotal().getMb();
                diskAvailableMB += path.getAvailable().getMb();
            }
        } catch (IOException e) {
            logger.error("Failed to get fs info", e);
        }

        // Get heap info
        JvmStats jvmStats = JvmStats.jvmStats();
        int heapUsedPercent = jvmStats.getMem().getHeapUsedPercent();  
        ByteSizeValue heapMax = jvmStats.getMem().getHeapMax();
        ByteSizeValue heapUsed = jvmStats.getMem().getHeapUsed();
        
        // Build heartbeat data as a Map
        Map<String, Object> heartbeatData = new HashMap<>();
        heartbeatData.put("timestamp", System.currentTimeMillis());
        heartbeatData.put("nodeName", nodeName);
        heartbeatData.put("nodeId", nodeId);
        heartbeatData.put("ephemeralId", ephemeralId);
        heartbeatData.put("heartbeatIntervalSeconds", HEARTBEAT_INTERVAL_SECONDS);
        heartbeatData.put("cpuUsedPercent", cpuPercent);
        heartbeatData.put("memoryUsedPercent", memoryPercent);
        heartbeatData.put("memoryMaxMB", memoryMax.getMb());
        heartbeatData.put("memoryUsedMB", memoryUsed.getMb());
        heartbeatData.put("heapMaxMB", heapMax.getMb());
        heartbeatData.put("heapUsedMB", heapUsed.getMb());
        heartbeatData.put("heapUsedPercent", heapUsedPercent);
        heartbeatData.put("diskTotalMB", diskTotalMB);
        heartbeatData.put("diskAvailableMB", diskAvailableMB);

        try {
            KV kvClient = etcdClient.getKVClient();
            
            // Convert Map to JSON using XContent
            ByteArrayOutputStream jsonStream = new ByteArrayOutputStream();
            try (XContentBuilder jsonBuilder = XContentType.JSON.contentBuilder(jsonStream)) {
                jsonBuilder.map(heartbeatData);
            }
            byte[] jsonBytes = jsonStream.toByteArray();
            
            ByteSequence value = ByteSequence.from(jsonBytes);
            kvClient.put(nodeStateKey, value).get();
        } catch (InterruptedException | ExecutionException | IOException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to publish heartbeat", e);
        }
    }
}
