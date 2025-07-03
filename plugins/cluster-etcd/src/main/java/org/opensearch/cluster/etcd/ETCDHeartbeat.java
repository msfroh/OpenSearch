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
import org.opensearch.monitor.jvm.JvmService;
import org.opensearch.monitor.jvm.JvmStats;
import org.opensearch.common.settings.Settings;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    public ETCDHeartbeat(String nodeName, String nodeId, String ephemeralId, Client etcdClient, NodeEnvironment nodeEnvironment) {
        this.nodeName = nodeName;
        this.nodeId = nodeId;
        this.ephemeralId = ephemeralId;
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
        

        try {
            KV kvClient = etcdClient.getKVClient();
            String heartbeatValue = String.format("{\"timestamp\":%d,\"nodeName\":\"%s\",\"nodeId\":\"%s\",\"ephemeralId\":\"%s\", \"heartbeatIntervalSeconds\":%d,\"cpuUsedPercent\":%d,\"memoryUsedPercent\":%d,\"memoryMaxMB\":%d,\"memoryUsedMB\":%d,\"heapMaxMB\":%d,\"heapUsedMB\":%d,\"heapUsedPercent\":%d,\"diskTotalMB\":%d,\"diskAvailableMB\":%d}",
                System.currentTimeMillis(), nodeName, nodeId, ephemeralId, HEARTBEAT_INTERVAL_SECONDS, cpuPercent, memoryPercent, memoryMax.getMb(), memoryUsed.getMb(), heapMax.getMb(), heapUsed.getMb(), heapUsedPercent, diskTotalMB, diskAvailableMB);
            ByteSequence value = ByteSequence.from(heartbeatValue, StandardCharsets.UTF_8);
            kvClient.put(nodeStateKey, value).get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to publish heartbeat", e);
        }
    }
}
