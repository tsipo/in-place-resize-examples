#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="${SCRIPT_DIR}/../charts/resize"

sleep 10

DEPLOYMENT_NAME="resize-cpu-limits-demo"

NAMESPACE="app"

echo "Installing chart manifests..."
helm upgrade --install -n ${NAMESPACE} --create-namespace --set nameOverride=${DEPLOYMENT_NAME} "$@" ${DEPLOYMENT_NAME} "${CHART_DIR}"

echo "$(date): Waiting for pod to be ready..."
kubectl rollout status deployment/${DEPLOYMENT_NAME} -n ${NAMESPACE} --timeout=60s

POD_NAME=$(kubectl get pods -n ${NAMESPACE} -l app="${DEPLOYMENT_NAME}" -o jsonpath='{.items[0].metadata.name}')
echo "$(date): Pod ${POD_NAME} is running in namespace ${NAMESPACE}. Waiting 1m for Prometheus to capture baseline..."
sleep 60

echo "--- $(date): Increasing CPU limit to 1000m ---"
kubectl patch pod ${POD_NAME} -n ${NAMESPACE} --subresource resize --patch '{"spec":{"containers":[{"name":"app","resources":{"limits":{"cpu":"1000m"}}}]}}'
echo "Running for 1m..."
sleep 60

echo "--- $(date): Increasing CPU limit to 2200m (> 2000m minimum) ---"
kubectl patch pod ${POD_NAME} -n ${NAMESPACE} --subresource resize --patch '{"spec":{"containers":[{"name":"app","resources":{"limits":{"cpu":"2200m"}}}]}}'
echo "Running for 1m..."
sleep 60

echo "--- $(date): Decreasing CPU limit back to 500m ---"
kubectl patch pod ${POD_NAME} -n ${NAMESPACE} --subresource resize --patch '{"spec":{"containers":[{"name":"app","resources":{"limits":{"cpu":"500m"}}}]}}'
echo "Running for 1m..."
sleep 60

kubectl describe pod ${POD_NAME} -n ${NAMESPACE}
echo "--- Current log: ---"
kubectl logs ${POD_NAME} -n ${NAMESPACE}
echo "--- End log ---"

echo "$(date): cleaning up"
helm delete -n ${NAMESPACE} ${DEPLOYMENT_NAME}
