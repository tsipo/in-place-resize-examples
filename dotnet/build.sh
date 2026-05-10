#!/bin/bash

set -e

IMAGE_NAME="resize-demo-dotnet:latest"

echo "Building Docker image $IMAGE_NAME..."
docker build -t $IMAGE_NAME -f Dockerfile .

echo "Loading image into kind cluster..."
kind load docker-image $IMAGE_NAME

echo "Done. You can now run the resize scripts."
