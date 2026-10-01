#!/usr/bin/env bash
set -euo pipefail

# Usage: bash monitoring/run-k6.sh [k6 run options] /path/to/scenario.js
export K6_PROMETHEUS_RW_SERVER_URL="${K6_PROMETHEUS_RW_SERVER_URL:-http://localhost:9090/api/v1/write}"
export K6_PROMETHEUS_RW_TREND_STATS="${K6_PROMETHEUS_RW_TREND_STATS:-avg,p(95),p(99),max}"
export K6_PROMETHEUS_RW_STALE_MARKERS="${K6_PROMETHEUS_RW_STALE_MARKERS:-true}"

exec k6 run --out experimental-prometheus-rw "$@"
