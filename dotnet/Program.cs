using Prometheus;
using Prometheus.DotNetRuntime;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://0.0.0.0:8080");

using var runtimeStats = DotNetRuntimeStatsBuilder.Default().StartCollecting();

var processorCount = Metrics.CreateGauge(
    "app_dotnet_processor_count",
    "Current Environment.ProcessorCount reported by the .NET runtime.");
var gcTotalAvailableMemoryBytes = Metrics.CreateGauge(
    "app_dotnet_gc_total_available_memory_bytes",
    "Current GC total available memory in bytes from GC.GetGCMemoryInfo().TotalAvailableMemoryBytes.");
var gcMemoryLoadBytes = Metrics.CreateGauge(
    "app_dotnet_gc_memory_load_bytes",
    "Current memory load in bytes from GC.GetGCMemoryInfo().MemoryLoadBytes.");
var gcRefreshFailures = Metrics.CreateCounter(
    "app_dotnet_gc_refresh_memory_limit_failures_total",
    "Number of failed GC.RefreshMemoryLimit calls.");

var slidingWindow = new List<byte[]>();
var permanentLeak = new List<byte[]>();

var autoRefreshMemoryLimit = ParseBool("AUTO_REFRESH_MEMORY_LIMIT")
    || ParseBool("AUTO_MEM_LIMIT");
var leakMemory = ParseBool("LEAK_MEMORY");

Console.WriteLine(
    $"Auto GC memory limit refresh requested by env var AUTO_REFRESH_MEMORY_LIMIT or AUTO_MEM_LIMIT: {autoRefreshMemoryLimit}");
Console.WriteLine($"Continuous memory leak requested by env var LEAK_MEMORY: {leakMemory}");

var app = builder.Build();

app.UseHttpMetrics();
app.MapGet("/", () => "resize-demo-dotnet");
app.MapMetrics();

var workLoop = RunWorkloadAsync(
    slidingWindow,
    permanentLeak,
    leakMemory,
    autoRefreshMemoryLimit,
    processorCount,
    gcTotalAvailableMemoryBytes,
    gcMemoryLoadBytes,
    gcRefreshFailures,
    app.Lifetime.ApplicationStopping);

Console.WriteLine("Starting metrics server on :8080");
await app.RunAsync();
await workLoop;

static async Task RunWorkloadAsync(
    List<byte[]> slidingWindow,
    List<byte[]> permanentLeak,
    bool leakMemory,
    bool autoRefreshMemoryLimit,
    Gauge processorCount,
    Gauge gcTotalAvailableMemoryBytes,
    Gauge gcMemoryLoadBytes,
    Counter gcRefreshFailures,
    CancellationToken cancellationToken)
{
    using var timer = new PeriodicTimer(TimeSpan.FromSeconds(2));

    UpdateMetrics(processorCount, gcTotalAvailableMemoryBytes, gcMemoryLoadBytes);

    try
    {
        while (await timer.WaitForNextTickAsync(cancellationToken))
        {
            if (autoRefreshMemoryLimit)
            {
                try
                {
                    GC.RefreshMemoryLimit();
                }
                catch (InvalidOperationException ex)
                {
                    gcRefreshFailures.Inc();
                    Console.Error.WriteLine($"GC.RefreshMemoryLimit failed: {ex.Message}");
                }
            }

            var totalAvailableMemoryBytes = UpdateMetrics(
                processorCount,
                gcTotalAvailableMemoryBytes,
                gcMemoryLoadBytes);

            slidingWindow.Add(AllocateTwoMiB());
            if (slidingWindow.Count > 25)
            {
                slidingWindow[0] = Array.Empty<byte>();
                slidingWindow.RemoveAt(0);
            }

            if (leakMemory)
            {
                permanentLeak.Add(AllocateTwoMiB());
            }

            DoWork(500_000);

            var limit = totalAvailableMemoryBytes > 0
                ? $"{totalAvailableMemoryBytes / (1024 * 1024)} MiB"
                : "unknown";

            Console.WriteLine(
                $"Limit: {limit} | Window: {slidingWindow.Count * 2} MiB | Leak: {permanentLeak.Count * 2} MiB | ProcessorCount: {Environment.ProcessorCount}");
        }
    }
    catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
    {
    }
}

static long UpdateMetrics(
    Gauge processorCount,
    Gauge gcTotalAvailableMemoryBytes,
    Gauge gcMemoryLoadBytes)
{
    var memoryInfo = GC.GetGCMemoryInfo();

    processorCount.Set(Environment.ProcessorCount);
    gcTotalAvailableMemoryBytes.Set(memoryInfo.TotalAvailableMemoryBytes);
    gcMemoryLoadBytes.Set(memoryInfo.MemoryLoadBytes);

    return memoryInfo.TotalAvailableMemoryBytes;
}

static byte[] AllocateTwoMiB()
{
    var buffer = new byte[2 * 1024 * 1024];
    for (var i = 0; i < buffer.Length; i++)
    {
        buffer[i] = (byte)(i % 256);
    }

    return buffer;
}

static void DoWork(int iterations)
{
    var value = 0.0;
    for (var i = 0; i < iterations; i++)
    {
        value += Math.Sqrt(i);
    }

    GC.KeepAlive(value);
}

static bool ParseBool(string name)
{
    return bool.TryParse(Environment.GetEnvironmentVariable(name), out var value) && value;
}
