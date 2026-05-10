#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"

"${SCRIPT_DIR}/../run/resize-cpu.sh" --set-string 'image.repository=resize-demo-rust' > "${RESULTS_DIR}/rust-processor-count.log"
