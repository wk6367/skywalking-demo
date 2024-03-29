package pers.wk.skywalking;

import com.sun.deploy.util.StringUtils;
import org.apache.commons.io.IOUtils;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.conf.SnifferConfigInitializer;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.util.StringUtil;

import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class SkyWalkingDemo {

    private static final ILog logger = LogManager.getLogger(SkyWalkingDemo.class);

    private static AtomicLong counter = new AtomicLong();

    private static String tIdLogDirStr;

    private static List<String> emptyTraceIds = new ArrayList<>();

    public static void main(String[] args) throws InterruptedException {
        try {
            SnifferConfigInitializer.initialize(null);
            ServiceManager.INSTANCE.boot();
        } catch (Exception e) {
            logger.error("error", e);
        }

        String threadNumStr = System.getProperty("DEMO_THREAD_NUM", "1");
        int threadNum = Integer.valueOf(threadNumStr);

        String intervalStr = System.getProperty("DEMO_INTERVAL", "20");
        int interval = Integer.valueOf(intervalStr);

        tIdLogDirStr = System.getProperty("DEMO_TRACE_ID_LOG_DIR", "/skywalking/invalidTraceIds.log");

        logger.info("threadNum {}, interval {}", threadNumStr, intervalStr);

        // 等连接上backend
        Thread.sleep(15000);

        ExecutorService executorService = Executors.newFixedThreadPool(threadNum + 1);
        for (int i = 0; i < threadNum; i++) {
            executorService.submit(() -> {
                while (true) {
                    mockTrace(interval);
                }
            });
        }

        executorService.submit(() -> {
            while (true) {
                logger.info("TPS {}", counter.get());
                counter.set(0);

                if (emptyTraceIds.size() > 0) {
                    try {
                        FileOutputStream fileOutputStream = new FileOutputStream(tIdLogDirStr, true);
                        IOUtils.write(StringUtils.join(emptyTraceIds, ",") + "\n", fileOutputStream, Charset.forName("UTF-8"));
                        fileOutputStream.close();
                        logger.info("write {} traceIds", emptyTraceIds.size());
                    } catch (Exception e) {
                        logger.error("error", e);
                    }
                    emptyTraceIds.clear();
                }

                Thread.sleep(1000);
            }
        });
    }

    public static void mockTrace(long interval) {
        ContextCarrier contextCarrier = new ContextCarrier();
        AbstractSpan exitSpan = ContextManager.createExitSpan("wk-test-client", contextCarrier, "localhost:8080");
        Map<String, String> headerMap = new HashMap<>();
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            headerMap.put(next.getHeadKey(), next.getHeadValue());
        }
        exitSpan.setComponent(ComponentsDefine.DUBBO);
        exitSpan.setLayer(SpanLayer.RPC_FRAMEWORK);

        String globalTraceId = ContextManager.getGlobalTraceId();
        if (StringUtil.isEmpty(globalTraceId) || globalTraceId.equals("[Ignored Trace]")) {
            emptyTraceIds.add(globalTraceId);
        }

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            mockRPC(headerMap, interval);
        });
        try {
            future.get();
        } catch (Exception e) {
            logger.error("error", e);
        }
        ContextManager.stopSpan();
        counter.incrementAndGet();
    }

    public static void mockRPC(Map<String, String> headerMap, long interval) {
        ContextCarrier contextCarrier = new ContextCarrier();
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            next.setHeadValue(headerMap.get(next.getHeadKey()));
        }

        AbstractSpan entrySpan = ContextManager.createEntrySpan("wk-test-server", contextCarrier);
        entrySpan.setComponent(ComponentsDefine.DUBBO);
        entrySpan.setLayer(SpanLayer.RPC_FRAMEWORK);

        String globalTraceId = ContextManager.getGlobalTraceId();
        if (StringUtil.isEmpty(globalTraceId) || globalTraceId.equals("[Ignored Trace]")) {
            emptyTraceIds.add(globalTraceId);
        }

        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
        }

        ContextManager.stopSpan();
    }
}
