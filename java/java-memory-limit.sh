#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"

"${SCRIPT_DIR}/../run/resize-mem.sh" --set-string 'image.repository=resize-demo-java' > "${RESULTS_DIR}/java-memory-limit.log"
