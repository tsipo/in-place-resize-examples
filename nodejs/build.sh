#!/bin/bash

set -e

IMAGE_NAME="resize-demo-nodejs:latest"

echo "Building Docker image $IMAGE_NAME..."
docker build -t "$IMAGE_NAME" -f Dockerfile .

echo "Loading image into kind cluster..."
# Assuming the kind cluster name is 'kind'
kind load docker-image "$IMAGE_NAME"

echo "Done. You can now run the resize scripts."
