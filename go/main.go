package main

import (
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"os"
	"runtime"
	"runtime/debug"
	"strconv"
	"time"

	"github.com/KimMachineGun/automemlimit/memlimit"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
	goMaxProcs = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "app_gomaxprocs",
		Help: "Current GOMAXPROCS",
	})
	goMemLimit = promauto.NewGauge(prometheus.GaugeOpts{
		Name: "app_gomemlimit",
		Help: "Current GOMEMLIMIT in bytes (0 means unlimited)",
	})
	slidingWindow [][]byte
	permanentLeak [][]byte
)

func update() int64 {
	// Update GOMAXPROCS (Natively dynamic in Go 1.25+)
	goMaxProcs.Set(float64(runtime.GOMAXPROCS(0)))

	limit := debug.SetMemoryLimit(-1)

	// Map "Unlimited" (math.MaxInt64) to 0 to fix your scale issue
	if limit == math.MaxInt64 {
		goMemLimit.Set(0)
	} else {
		goMemLimit.Set(float64(limit))
	}
	return limit
}

func autoMemLimit() {
	_, err := memlimit.SetGoMemLimitWithOpts(
		memlimit.WithProvider(memlimit.FromCgroup),
		memlimit.WithRatio(0.80), // choose based on non-Go memory overhead
		memlimit.WithRefreshInterval(5*time.Second),
		memlimit.WithLogger(slog.Default()),
	)
	if err != nil {
		slog.Warn("failed to set Go memory limit from cgroup", "err", err)
	}

}

func main() {
	if autoMemoryLimit, _ := strconv.ParseBool(os.Getenv("AUTO_MEM_LIMIT")); autoMemoryLimit {
		fmt.Printf("Auto mem limit requested by env var AUTO_MEM_LIMIT: %t\n", autoMemoryLimit)
		autoMemLimit()
	}

	update()

	http.Handle("/metrics", promhttp.Handler())
	go func() {
		fmt.Println("Starting metrics server on :8080")
		if err := http.ListenAndServe(":8080", nil); err != nil {
			fmt.Fprintf(os.Stderr, "Server failed: %v\n", err)
		}
	}()

	leakMem, _ := strconv.ParseBool(os.Getenv("LEAK_MEMORY"))
	fmt.Printf("Continuous memory leak requested by env var LEAK_MEMORY: %t\n", leakMem)
	// 3. Main loop with 2s ticker
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		limit := update()

		// Allocate 2MiB into the sliding window (reclaimable)
		buf := make([]byte, 2*1024*1024)
		for i := range buf {
			buf[i] = byte(i % 256)
		}
		slidingWindow = append(slidingWindow, buf)
		if len(slidingWindow) > 25 {
			slidingWindow[0] = nil // Allow GC to reclaim the 2MiB buffer
			slidingWindow = slidingWindow[1:]
		}

		if leakMem {
			// Allocate 2MiB permanent leak
			leak := make([]byte, 2*1024*1024)
			for i := range leak {
				leak[i] = byte(i % 256)
			}
			permanentLeak = append(permanentLeak, leak)
		}

		// CPU load
		doWork(500000)

		limitStr := "unlimited"
		if limit != math.MaxInt64 {
			limitStr = fmt.Sprintf("%d MiB", limit/(1024*1024))
		}
		fmt.Printf("Limit: %s | Window: %d MiB | Leak: %d MiB | GOMAXPROCS: %d\n",
			limitStr, len(slidingWindow)*2, len(permanentLeak)*2, runtime.GOMAXPROCS(0))
	}
}

func doWork(iterations int) {
	val := 0.0
	for i := 0; i < iterations; i++ {
		val += math.Sqrt(float64(i))
	}
	_ = val
}
