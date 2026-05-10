#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"

"${SCRIPT_DIR}/../run/resize-mem.sh" --set-string 'image.repository=resize-demo-go,env[0].name=GOMEMLIMIT,env[0].value=256MiB,env[1].name=AUTO_MEM_LIMIT,env[1].value=true' > "${RESULTS_DIR}/gomemlimit-both.log"
