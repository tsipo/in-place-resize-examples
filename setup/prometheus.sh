#!/bin/bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install -n monitoring --create-namespace \
	-f prometheus.yaml \
	prometheus prometheus-community/prometheus
