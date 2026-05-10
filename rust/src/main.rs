use std::env;
use std::fs;
use std::hint::black_box;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const PORT: u16 = 8080;
const ALLOCATION_BYTES: usize = 2 * 1024 * 1024;
const WINDOW_SIZE: usize = 25;
const CPU_ITERATIONS: usize = 500_000;

type Buffers = Arc<Mutex<Vec<Vec<u8>>>>;

#[derive(Clone)]
struct AppState {
    sliding_window: Buffers,
    permanent_leak: Buffers,
}

#[derive(Default)]
struct RuntimeSnapshot {
    available_parallelism: u64,
    host_logical_cpus: u64,
    cgroup_cpu_limit_cores: f64,
    cgroup_memory_limit_bytes: u64,
    cgroup_memory_current_bytes: u64,
    cgroup_pids_current: u64,
    cgroup_pids_max: u64,
    process_rss_bytes: u64,
    process_virtual_memory_bytes: u64,
    process_threads: u64,
    sliding_window_bytes: u64,
    permanent_leak_bytes: u64,
}

fn main() {
    let state = AppState {
        sliding_window: Arc::new(Mutex::new(Vec::new())),
        permanent_leak: Arc::new(Mutex::new(Vec::new())),
    };

    let leak_memory = parse_bool_env("LEAK_MEMORY");
    println!("Continuous memory leak requested by env var LEAK_MEMORY: {leak_memory}");

    let server_state = state.clone();
    thread::spawn(move || start_metrics_server(server_state));

    loop {
        thread::sleep(Duration::from_secs(2));

        {
            let mut sliding_window = state
                .sliding_window
                .lock()
                .expect("sliding window lock poisoned");
            sliding_window.push(allocate_buffer());
            if sliding_window.len() > WINDOW_SIZE {
                sliding_window.remove(0);
            }
        }

        if leak_memory {
            state
                .permanent_leak
                .lock()
                .expect("permanent leak lock poisoned")
                .push(allocate_buffer());
        }

        do_work(CPU_ITERATIONS);

        let snapshot = snapshot_runtime(&state);
        println!(
            "Limit: {} | Current: {} MiB | Window: {} MiB | Leak: {} MiB | available_parallelism: {}",
            format_limit(snapshot.cgroup_memory_limit_bytes),
            snapshot.cgroup_memory_current_bytes / mib(),
            snapshot.sliding_window_bytes / mib(),
            snapshot.permanent_leak_bytes / mib(),
            snapshot.available_parallelism,
        );
    }
}

fn start_metrics_server(state: AppState) {
    let listener = TcpListener::bind(("0.0.0.0", PORT)).expect("failed to bind metrics server");
    println!("Starting metrics server on :{PORT}");

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                let state = state.clone();
                thread::spawn(move || handle_connection(stream, &state));
            }
            Err(err) => eprintln!("metrics server connection failed: {err}"),
        }
    }
}

fn handle_connection(mut stream: TcpStream, state: &AppState) {
    let mut buffer = [0_u8; 1024];
    let bytes_read = match stream.read(&mut buffer) {
        Ok(bytes_read) => bytes_read,
        Err(err) => {
            eprintln!("failed to read request: {err}");
            return;
        }
    };

    let request = String::from_utf8_lossy(&buffer[..bytes_read]);
    let path = request
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .unwrap_or("/");

    if path == "/metrics" {
        let body = collect_metrics(state);
        write_response(
            &mut stream,
            "200 OK",
            "text/plain; version=0.0.4; charset=utf-8",
            &body,
        );
    } else if path == "/" {
        write_response(
            &mut stream,
            "200 OK",
            "text/plain; charset=utf-8",
            "resize-demo-rust: scrape /metrics\n",
        );
    } else {
        write_response(
            &mut stream,
            "404 Not Found",
            "text/plain; charset=utf-8",
            "not found\n",
        );
    }
}

fn write_response(stream: &mut TcpStream, status: &str, content_type: &str, body: &str) {
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );

    if let Err(err) = stream.write_all(response.as_bytes()) {
        eprintln!("failed to write response: {err}");
    }
}

fn collect_metrics(state: &AppState) -> String {
    let snapshot = snapshot_runtime(state);
    let mut out = String::with_capacity(4096);

    append_gauge(
        &mut out,
        "app_rust_available_parallelism",
        "std::thread::available_parallelism as seen by Rust",
        snapshot.available_parallelism as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_host_logical_cpus",
        "Logical CPUs visible in /proc/cpuinfo",
        snapshot.host_logical_cpus as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_cgroup_cpu_limit_cores",
        "CPU limit detected from cgroup quota in cores, 0 means unlimited",
        snapshot.cgroup_cpu_limit_cores,
    );
    append_gauge(
        &mut out,
        "app_rust_cgroup_memory_limit_bytes",
        "Memory limit detected from cgroup in bytes, 0 means unlimited",
        snapshot.cgroup_memory_limit_bytes as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_cgroup_memory_current_bytes",
        "Current cgroup memory usage in bytes",
        snapshot.cgroup_memory_current_bytes as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_cgroup_pids_current",
        "Current number of processes or threads in the cgroup",
        snapshot.cgroup_pids_current as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_cgroup_pids_max",
        "cgroup pids.max value, 0 means unlimited or unavailable",
        snapshot.cgroup_pids_max as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_process_rss_bytes",
        "Resident set size from /proc/self/status in bytes",
        snapshot.process_rss_bytes as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_process_virtual_memory_bytes",
        "Virtual memory size from /proc/self/status in bytes",
        snapshot.process_virtual_memory_bytes as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_process_threads",
        "Thread count from /proc/self/status",
        snapshot.process_threads as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_sliding_window_bytes",
        "Bytes retained in the reclaimable sliding window",
        snapshot.sliding_window_bytes as f64,
    );
    append_gauge(
        &mut out,
        "app_rust_permanent_leak_bytes",
        "Bytes retained permanently when LEAK_MEMORY=true",
        snapshot.permanent_leak_bytes as f64,
    );

    out
}

fn append_gauge(out: &mut String, name: &str, help: &str, value: f64) {
    out.push_str("# HELP ");
    out.push_str(name);
    out.push(' ');
    out.push_str(help);
    out.push('\n');
    out.push_str("# TYPE ");
    out.push_str(name);
    out.push_str(" gauge\n");
    out.push_str(name);
    out.push(' ');
    out.push_str(&value.to_string());
    out.push('\n');
}

fn snapshot_runtime(state: &AppState) -> RuntimeSnapshot {
    let sliding_window_bytes = retained_bytes(&state.sliding_window);
    let permanent_leak_bytes = retained_bytes(&state.permanent_leak);
    let process = read_process_status();

    RuntimeSnapshot {
        available_parallelism: thread::available_parallelism()
            .map(|parallelism| parallelism.get() as u64)
            .unwrap_or(0),
        host_logical_cpus: host_logical_cpus(),
        cgroup_cpu_limit_cores: cgroup_cpu_limit_cores(),
        cgroup_memory_limit_bytes: cgroup_memory_limit_bytes(),
        cgroup_memory_current_bytes: read_cgroup_number(&[
            "/sys/fs/cgroup/memory.current",
            "/sys/fs/cgroup/memory/memory.usage_in_bytes",
        ])
        .unwrap_or(0),
        cgroup_pids_current: read_cgroup_number(&[
            "/sys/fs/cgroup/pids.current",
            "/sys/fs/cgroup/pids/pids.current",
        ])
        .unwrap_or(0),
        cgroup_pids_max: read_cgroup_number(&[
            "/sys/fs/cgroup/pids.max",
            "/sys/fs/cgroup/pids/pids.max",
        ])
        .unwrap_or(0),
        process_rss_bytes: process.rss_bytes,
        process_virtual_memory_bytes: process.virtual_memory_bytes,
        process_threads: process.threads,
        sliding_window_bytes,
        permanent_leak_bytes,
    }
}

fn retained_bytes(buffers: &Buffers) -> u64 {
    buffers
        .lock()
        .expect("buffer lock poisoned")
        .iter()
        .map(|buffer| buffer.len() as u64)
        .sum()
}

fn cgroup_memory_limit_bytes() -> u64 {
    if let Some(limit) = read_cgroup_number(&["/sys/fs/cgroup/memory.max"]) {
        return limit;
    }

    let Some(limit) = read_cgroup_number(&["/sys/fs/cgroup/memory/memory.limit_in_bytes"]) else {
        return 0;
    };

    let host_memory = read_mem_total_bytes();
    if host_memory > 0 && limit >= host_memory {
        0
    } else {
        limit
    }
}

fn cgroup_cpu_limit_cores() -> f64 {
    if let Ok(raw) = fs::read_to_string("/sys/fs/cgroup/cpu.max") {
        let fields: Vec<&str> = raw.split_whitespace().collect();
        if fields.len() >= 2 && fields[0] != "max" {
            if let (Ok(quota), Ok(period)) = (fields[0].parse::<f64>(), fields[1].parse::<f64>()) {
                if quota > 0.0 && period > 0.0 {
                    return quota / period;
                }
            }
        }
    }

    let quota = read_cgroup_number(&["/sys/fs/cgroup/cpu/cpu.cfs_quota_us"]);
    let period = read_cgroup_number(&["/sys/fs/cgroup/cpu/cpu.cfs_period_us"]);
    match (quota, period) {
        (Some(quota), Some(period)) if quota > 0 && period > 0 => quota as f64 / period as f64,
        _ => 0.0,
    }
}

fn read_cgroup_number(paths: &[&str]) -> Option<u64> {
    paths.iter().find_map(|path| {
        let raw = fs::read_to_string(path).ok()?;
        let raw = raw.trim();
        if raw.is_empty() || raw == "max" {
            return Some(0);
        }
        raw.parse::<u64>().ok()
    })
}

#[derive(Default)]
struct ProcessStatus {
    rss_bytes: u64,
    virtual_memory_bytes: u64,
    threads: u64,
}

fn read_process_status() -> ProcessStatus {
    let Ok(status) = fs::read_to_string("/proc/self/status") else {
        return ProcessStatus::default();
    };

    let mut process = ProcessStatus::default();
    for line in status.lines() {
        if let Some(value) = line.strip_prefix("VmRSS:") {
            process.rss_bytes = parse_kib_line(value);
        } else if let Some(value) = line.strip_prefix("VmSize:") {
            process.virtual_memory_bytes = parse_kib_line(value);
        } else if let Some(value) = line.strip_prefix("Threads:") {
            process.threads = value.trim().parse::<u64>().unwrap_or(0);
        }
    }

    process
}

fn parse_kib_line(value: &str) -> u64 {
    value
        .split_whitespace()
        .next()
        .and_then(|number| number.parse::<u64>().ok())
        .unwrap_or(0)
        * 1024
}

fn host_logical_cpus() -> u64 {
    fs::read_to_string("/proc/cpuinfo")
        .map(|cpuinfo| {
            cpuinfo
                .lines()
                .filter(|line| line.starts_with("processor"))
                .count() as u64
        })
        .unwrap_or(0)
}

fn read_mem_total_bytes() -> u64 {
    let Ok(meminfo) = fs::read_to_string("/proc/meminfo") else {
        return 0;
    };

    meminfo
        .lines()
        .find_map(|line| line.strip_prefix("MemTotal:").map(parse_kib_line))
        .unwrap_or(0)
}

fn allocate_buffer() -> Vec<u8> {
    let mut buffer = vec![0_u8; ALLOCATION_BYTES];
    for (index, byte) in buffer.iter_mut().enumerate() {
        *byte = (index % 256) as u8;
    }
    buffer
}

fn do_work(iterations: usize) {
    let mut value = 0.0_f64;
    for index in 0..iterations {
        value += (index as f64).sqrt();
    }
    black_box(value);
}

fn parse_bool_env(name: &str) -> bool {
    env::var(name)
        .map(|value| {
            matches!(
                value.to_ascii_lowercase().as_str(),
                "1" | "true" | "yes" | "on"
            )
        })
        .unwrap_or(false)
}

fn format_limit(bytes: u64) -> String {
    if bytes == 0 {
        "unlimited".to_string()
    } else {
        format!("{} MiB", bytes / mib())
    }
}

fn mib() -> u64 {
    1024 * 1024
}
