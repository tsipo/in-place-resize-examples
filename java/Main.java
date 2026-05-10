import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

public class Main {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final int ALLOCATION_BYTES = 2 * 1024 * 1024;
    private static final int WINDOW_SIZE = 25;
    private static final int CPU_ITERATIONS = 500_000;

    private static final List<byte[]> slidingWindow = new ArrayList<>();
    private static final List<byte[]> permanentLeak = new ArrayList<>();
    private static final RuntimeLimits runtimeLimits = new RuntimeLimits();
    private static final ThreadLocal<Set<String>> emittedMetricHeaders = ThreadLocal.withInitial(HashSet::new);

    public interface RuntimeLimitsMXBean {
        int getAvailableProcessors();

        long getMaxHeapBytes();

        long getSoftMaxHeapBytes();

        long getVmOptionMaxHeapBytes();

        long getVmOptionSoftMaxHeapBytes();

        long getContainerAwareTotalMemoryBytes();

        long getContainerAwareFreeMemoryBytes();

        long getSlidingWindowBytes();

        long getPermanentLeakBytes();
    }

    public static final class RuntimeLimits implements RuntimeLimitsMXBean {
        @Override
        public int getAvailableProcessors() {
            return Runtime.getRuntime().availableProcessors();
        }

        @Override
        public long getMaxHeapBytes() {
            return Runtime.getRuntime().maxMemory();
        }

        @Override
        public long getSoftMaxHeapBytes() {
            long softMaxHeap = getVmOptionLong("SoftMaxHeapSize");
            return softMaxHeap >= 0 ? softMaxHeap : getMaxHeapBytes();
        }

        @Override
        public long getVmOptionMaxHeapBytes() {
            return getVmOptionLong("MaxHeapSize");
        }

        @Override
        public long getVmOptionSoftMaxHeapBytes() {
            return getVmOptionLong("SoftMaxHeapSize");
        }

        @Override
        public long getContainerAwareTotalMemoryBytes() {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            return osBean == null ? -1 : osBean.getTotalMemorySize();
        }

        @Override
        public long getContainerAwareFreeMemoryBytes() {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            return osBean == null ? -1 : osBean.getFreeMemorySize();
        }

        @Override
        public long getSlidingWindowBytes() {
            synchronized (slidingWindow) {
                return (long) slidingWindow.size() * ALLOCATION_BYTES;
            }
        }

        @Override
        public long getPermanentLeakBytes() {
            synchronized (permanentLeak) {
                return (long) permanentLeak.size() * ALLOCATION_BYTES;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        registerRuntimeLimitsMBean();
        startMetricsServer();

        boolean leakMemory = Boolean.parseBoolean(System.getenv().getOrDefault("LEAK_MEMORY", "false"));
        System.out.printf("Continuous memory leak requested by env var LEAK_MEMORY: %s%n", leakMemory);

        while (true) {
            Thread.sleep(2_000);
            allocateWindowBuffer();

            if (leakMemory) {
                allocateLeakBuffer();
            }

            doWork(CPU_ITERATIONS);

            System.out.printf(
                    Locale.ROOT,
                    "MaxHeap: %d MiB | SoftMaxHeap: %d MiB | Window: %d MiB | Leak: %d MiB | AvailableProcessors: %d%n",
                    runtimeLimits.getMaxHeapBytes() / (1024 * 1024),
                    runtimeLimits.getSoftMaxHeapBytes() / (1024 * 1024),
                    runtimeLimits.getSlidingWindowBytes() / (1024 * 1024),
                    runtimeLimits.getPermanentLeakBytes() / (1024 * 1024),
                    runtimeLimits.getAvailableProcessors());
        }
    }

    private static void registerRuntimeLimitsMBean() throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("resize.demo:type=RuntimeLimits");
        if (!server.isRegistered(name)) {
            server.registerMBean(new StandardMBean(runtimeLimits, RuntimeLimitsMXBean.class, true), name);
        }
    }

    private static void startMetricsServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/metrics", Main::handleMetrics);
        server.createContext("/", Main::handleRoot);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        System.out.printf("Starting metrics server on :%d%n", PORT);
    }

    private static void handleMetrics(HttpExchange exchange) throws IOException {
        byte[] body = collectMetrics().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        byte[] body = "resize-demo-java: scrape /metrics\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String collectMetrics() {
        emittedMetricHeaders.get().clear();
        StringBuilder out = new StringBuilder(16_384);
        appendGauge(out, "app_available_processors", "Runtime.availableProcessors as seen by the JVM", runtimeLimits.getAvailableProcessors());
        appendGauge(out, "app_jvm_max_heap_bytes", "Runtime max heap in bytes", runtimeLimits.getMaxHeapBytes());
        appendGauge(out, "app_jvm_soft_max_heap_bytes", "HotSpot SoftMaxHeapSize in bytes, or max heap if unavailable", runtimeLimits.getSoftMaxHeapBytes());
        appendGauge(out, "app_jvm_vmoption_max_heap_bytes", "HotSpot MaxHeapSize VM option in bytes, -1 if unavailable", runtimeLimits.getVmOptionMaxHeapBytes());
        appendGauge(out, "app_jvm_vmoption_soft_max_heap_bytes", "HotSpot SoftMaxHeapSize VM option in bytes, -1 if unavailable", runtimeLimits.getVmOptionSoftMaxHeapBytes());
        appendGauge(out, "app_container_aware_total_memory_bytes", "OperatingSystemMXBean total memory in bytes", runtimeLimits.getContainerAwareTotalMemoryBytes());
        appendGauge(out, "app_container_aware_free_memory_bytes", "OperatingSystemMXBean free memory in bytes", runtimeLimits.getContainerAwareFreeMemoryBytes());
        appendGauge(out, "app_sliding_window_bytes", "Bytes retained in the reclaimable sliding window", runtimeLimits.getSlidingWindowBytes());
        appendGauge(out, "app_permanent_leak_bytes", "Bytes retained permanently when LEAK_MEMORY=true", runtimeLimits.getPermanentLeakBytes());

        appendMemoryMetrics(out);
        appendMemoryPoolMetrics(out);
        appendThreadMetrics(out);
        appendGarbageCollectorMetrics(out);
        appendClassLoadingMetrics(out);
        appendOperatingSystemMetrics(out);
        String metrics = out.toString();
        emittedMetricHeaders.remove();
        return metrics;
    }

    private static void appendMemoryMetrics(StringBuilder out) {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        appendMemoryUsage(out, "heap", memory.getHeapMemoryUsage());
        appendMemoryUsage(out, "nonheap", memory.getNonHeapMemoryUsage());
    }

    private static void appendMemoryUsage(StringBuilder out, String area, MemoryUsage usage) {
        appendGauge(out, "jvm_memory_used_bytes", "Used JVM memory", usage.getUsed(), "area", area);
        appendGauge(out, "jvm_memory_committed_bytes", "Committed JVM memory", usage.getCommitted(), "area", area);
        appendGauge(out, "jvm_memory_max_bytes", "Max JVM memory, -1 if undefined", usage.getMax(), "area", area);
        appendGauge(out, "jvm_memory_init_bytes", "Initial JVM memory", usage.getInit(), "area", area);
    }

    private static void appendMemoryPoolMetrics(StringBuilder out) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) {
                continue;
            }
            String poolName = pool.getName();
            appendGauge(out, "jvm_memory_pool_used_bytes", "Used JVM memory pool bytes", usage.getUsed(), "pool", poolName);
            appendGauge(out, "jvm_memory_pool_committed_bytes", "Committed JVM memory pool bytes", usage.getCommitted(), "pool", poolName);
            appendGauge(out, "jvm_memory_pool_max_bytes", "Max JVM memory pool bytes, -1 if undefined", usage.getMax(), "pool", poolName);
            appendGauge(out, "jvm_memory_pool_init_bytes", "Initial JVM memory pool bytes", usage.getInit(), "pool", poolName);
        }
    }

    private static void appendThreadMetrics(StringBuilder out) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        appendGauge(out, "jvm_threads_current", "Current live JVM thread count", threads.getThreadCount());
        appendGauge(out, "jvm_threads_daemon", "Current live daemon JVM thread count", threads.getDaemonThreadCount());
        appendGauge(out, "jvm_threads_peak", "Peak live JVM thread count", threads.getPeakThreadCount());
        appendGauge(out, "jvm_threads_started_total", "Total started JVM thread count", threads.getTotalStartedThreadCount());
    }

    private static void appendGarbageCollectorMetrics(StringBuilder out) {
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            appendGauge(out, "jvm_gc_collection_seconds_count", "JVM garbage collection count", gc.getCollectionCount(), "gc", gc.getName());
            appendGauge(out, "jvm_gc_collection_seconds_sum", "JVM garbage collection time in seconds", gc.getCollectionTime() / 1000.0, "gc", gc.getName());
        }
    }

    private static void appendClassLoadingMetrics(StringBuilder out) {
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        appendGauge(out, "jvm_classes_loaded", "Currently loaded JVM classes", classes.getLoadedClassCount());
        appendGauge(out, "jvm_classes_loaded_total", "Total loaded JVM classes", classes.getTotalLoadedClassCount());
        appendGauge(out, "jvm_classes_unloaded_total", "Total unloaded JVM classes", classes.getUnloadedClassCount());
    }

    private static void appendOperatingSystemMetrics(StringBuilder out) {
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        if (os == null) {
            return;
        }

        appendGauge(out, "process_cpu_seconds_total", "Process CPU time in seconds", os.getProcessCpuTime() / 1_000_000_000.0);
        appendGauge(out, "process_cpu_load_ratio", "Recent process CPU load from 0.0 to 1.0, -1 if unavailable", os.getProcessCpuLoad());
        appendGauge(out, "system_cpu_load_ratio", "Recent system CPU load from 0.0 to 1.0, -1 if unavailable", os.getCpuLoad());
        appendGauge(out, "system_load_average", "System load average, -1 if unavailable", os.getSystemLoadAverage());
    }

    private static void appendGauge(StringBuilder out, String name, String help, double value, String... labels) {
        if (emittedMetricHeaders.get().add(name)) {
            out.append("# HELP ").append(name).append(' ').append(help).append('\n');
            out.append("# TYPE ").append(name).append(" gauge\n");
        }
        out.append(name);
        appendLabels(out, labels);
        out.append(' ').append(formatDouble(value)).append('\n');
    }

    private static void appendLabels(StringBuilder out, String... labels) {
        if (labels.length == 0) {
            return;
        }
        out.append('{');
        for (int i = 0; i < labels.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            out.append(labels[i]).append("=\"").append(escapeLabelValue(labels[i + 1])).append('"');
        }
        out.append('}');
    }

    private static String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static long getVmOptionLong(String name) {
        try {
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (bean == null) {
                return -1;
            }
            return Long.parseLong(bean.getVMOption(name).getValue());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void allocateWindowBuffer() {
        byte[] buf = allocateBuffer();
        synchronized (slidingWindow) {
            slidingWindow.add(buf);
            if (slidingWindow.size() > WINDOW_SIZE) {
                slidingWindow.removeFirst();
            }
        }
    }

    private static void allocateLeakBuffer() {
        byte[] leak = allocateBuffer();
        synchronized (permanentLeak) {
            permanentLeak.add(leak);
        }
    }

    private static byte[] allocateBuffer() {
        byte[] buf = new byte[ALLOCATION_BYTES];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (byte) (i % 256);
        }
        return buf;
    }

    private static void doWork(int iterations) {
        double val = 0.0;
        for (int i = 0; i < iterations; i++) {
            val += Math.sqrt(i);
        }
        if (val == -1.0) {
            System.out.println("unreachable");
        }
    }
}
