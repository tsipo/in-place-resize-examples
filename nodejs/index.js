'use strict';

const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const v8 = require('node:v8');
const client = require('prom-client');

const PORT = Number.parseInt(process.env.PORT || '8080', 10);
const ALLOCATION_SIZE = 2 * 1024 * 1024;
const MAX_WINDOW_BUFFERS = 25;

const registry = new client.Registry();
client.collectDefaultMetrics({ register: registry });

const gauges = {
  availableParallelism: new client.Gauge({
    name: 'app_nodejs_available_parallelism',
    help: 'Current os.availableParallelism() value'
  }),
  cpuCount: new client.Gauge({
    name: 'app_nodejs_cpu_count',
    help: 'Number of logical CPUs reported by os.cpus()'
  }),
  cgroupCpuLimit: new client.Gauge({
    name: 'app_nodejs_cgroup_cpu_limit_cores',
    help: 'CPU limit detected from cgroup quota in cores (0 means unlimited)'
  }),
  cgroupMemoryLimit: new client.Gauge({
    name: 'app_nodejs_cgroup_memory_limit_bytes',
    help: 'Memory limit detected from cgroup in bytes (0 means unlimited)'
  }),
  heapSizeLimit: new client.Gauge({
    name: 'app_nodejs_heap_size_limit_bytes',
    help: 'V8 heap size limit in bytes'
  }),
  totalHeapSize: new client.Gauge({
    name: 'app_nodejs_total_heap_size_bytes',
    help: 'V8 total heap size in bytes'
  }),
  usedHeapSize: new client.Gauge({
    name: 'app_nodejs_used_heap_size_bytes',
    help: 'V8 used heap size in bytes'
  }),
  totalAvailableHeapSize: new client.Gauge({
    name: 'app_nodejs_total_available_heap_size_bytes',
    help: 'V8 total available heap size in bytes'
  }),
  externalMemory: new client.Gauge({
    name: 'app_nodejs_external_memory_bytes',
    help: 'V8 external memory in bytes'
  }),
  rss: new client.Gauge({
    name: 'app_nodejs_rss_bytes',
    help: 'Resident set size in bytes'
  }),
  heapTotal: new client.Gauge({
    name: 'app_nodejs_process_heap_total_bytes',
    help: 'Node.js process heapTotal from process.memoryUsage() in bytes'
  }),
  heapUsed: new client.Gauge({
    name: 'app_nodejs_process_heap_used_bytes',
    help: 'Node.js process heapUsed from process.memoryUsage() in bytes'
  }),
  arrayBuffers: new client.Gauge({
    name: 'app_nodejs_array_buffers_bytes',
    help: 'Node.js process arrayBuffers memory in bytes'
  })
};

const heapSpaceGauge = new client.Gauge({
  name: 'app_nodejs_heap_space_size_bytes',
  help: 'V8 heap space size in bytes',
  labelNames: ['space', 'kind']
});

for (const gauge of Object.values(gauges)) {
  registry.registerMetric(gauge);
}
registry.registerMetric(heapSpaceGauge);

const slidingWindow = [];
const permanentLeak = [];

function parseBool(value) {
  return ['1', 'true', 'yes', 'on'].includes(String(value || '').toLowerCase());
}

function readNumber(path) {
  try {
    const raw = fs.readFileSync(path, 'utf8').trim();
    if (!raw || raw === 'max') {
      return null;
    }
    const value = Number.parseInt(raw, 10);
    return Number.isFinite(value) ? value : null;
  } catch {
    return null;
  }
}

function readFirstLine(path) {
  try {
    return fs.readFileSync(path, 'utf8').trim().split(/\s+/);
  } catch {
    return null;
  }
}

function getCgroupMemoryLimit() {
  const cgroupV2 = readNumber('/sys/fs/cgroup/memory.max');
  if (cgroupV2 !== null) {
    return cgroupV2;
  }

  const cgroupV1 = readNumber('/sys/fs/cgroup/memory/memory.limit_in_bytes');
  if (cgroupV1 === null) {
    return 0;
  }

  const hostTotal = os.totalmem();
  return cgroupV1 >= hostTotal ? 0 : cgroupV1;
}

function getCgroupCpuLimit() {
  const cpuMax = readFirstLine('/sys/fs/cgroup/cpu.max');
  if (cpuMax && cpuMax[0] !== 'max') {
    const quota = Number.parseInt(cpuMax[0], 10);
    const period = Number.parseInt(cpuMax[1], 10);
    if (Number.isFinite(quota) && Number.isFinite(period) && period > 0) {
      return quota / period;
    }
  }

  const quota = readNumber('/sys/fs/cgroup/cpu/cpu.cfs_quota_us');
  const period = readNumber('/sys/fs/cgroup/cpu/cpu.cfs_period_us');
  if (quota !== null && period !== null && quota > 0 && period > 0) {
    return quota / period;
  }

  return 0;
}

function updateMetrics() {
  const heapStats = v8.getHeapStatistics();
  const memory = process.memoryUsage();

  gauges.availableParallelism.set(os.availableParallelism());
  gauges.cpuCount.set(os.cpus().length);
  gauges.cgroupCpuLimit.set(getCgroupCpuLimit());
  gauges.cgroupMemoryLimit.set(getCgroupMemoryLimit());
  gauges.heapSizeLimit.set(heapStats.heap_size_limit);
  gauges.totalHeapSize.set(heapStats.total_heap_size);
  gauges.usedHeapSize.set(heapStats.used_heap_size);
  gauges.totalAvailableHeapSize.set(heapStats.total_available_size);
  gauges.externalMemory.set(heapStats.external_memory);
  gauges.rss.set(memory.rss);
  gauges.heapTotal.set(memory.heapTotal);
  gauges.heapUsed.set(memory.heapUsed);
  gauges.arrayBuffers.set(memory.arrayBuffers);

  for (const space of v8.getHeapSpaceStatistics()) {
    heapSpaceGauge.set({ space: space.space_name, kind: 'size' }, space.space_size);
    heapSpaceGauge.set({ space: space.space_name, kind: 'used' }, space.space_used_size);
    heapSpaceGauge.set({ space: space.space_name, kind: 'available' }, space.space_available_size);
    heapSpaceGauge.set({ space: space.space_name, kind: 'physical' }, space.physical_space_size);
  }

  return heapStats;
}

function allocateBuffer() {
  const buffer = Buffer.allocUnsafe(ALLOCATION_SIZE);
  for (let i = 0; i < buffer.length; i += 1) {
    buffer[i] = i % 256;
  }
  return buffer;
}

function doWork(iterations) {
  let value = 0;
  for (let i = 0; i < iterations; i += 1) {
    value += Math.sqrt(i);
  }
  return value;
}

async function metricsHandler(response) {
  updateMetrics();
  response.writeHead(200, { 'Content-Type': registry.contentType });
  response.end(await registry.metrics());
}

const server = http.createServer((request, response) => {
  if (request.url === '/metrics') {
    metricsHandler(response).catch((error) => {
      response.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end(`${error.stack || error.message}\n`);
    });
    return;
  }

  response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  response.end('not found\n');
});

const leakMemory = parseBool(process.env.LEAK_MEMORY);
console.log(`Continuous memory leak requested by env var LEAK_MEMORY: ${leakMemory}`);

updateMetrics();
server.listen(PORT, () => {
  console.log(`Starting metrics server on :${PORT}`);
});

setInterval(() => {
  const heapStats = updateMetrics();

  slidingWindow.push(allocateBuffer());
  if (slidingWindow.length > MAX_WINDOW_BUFFERS) {
    slidingWindow.shift();
  }

  if (leakMemory) {
    permanentLeak.push(allocateBuffer());
  }

  doWork(500000);

  console.log(
    [
      `Heap limit: ${Math.round(heapStats.heap_size_limit / 1024 / 1024)} MiB`,
      `Window: ${slidingWindow.length * 2} MiB`,
      `Leak: ${permanentLeak.length * 2} MiB`,
      `availableParallelism: ${os.availableParallelism()}`
    ].join(' | ')
  );
}, 2000);
