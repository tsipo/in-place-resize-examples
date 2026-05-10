#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"

"${SCRIPT_DIR}/../run/resize-mem.sh" --set-string 'image.repository=resize-demo-go' > "${RESULTS_DIR}/gomemlimit-none.log"
