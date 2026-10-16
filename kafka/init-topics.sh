#!/bin/sh
set -eu

bootstrap_servers=kafka:19092

if [ "$#" -eq 0 ] || [ "$(( $# % 2 ))" -ne 0 ]; then
    echo "Expected request/result topic and DLT topic pairs" >&2
    exit 1
fi

configure_topic() {
    topic=$1
    retention_ms=$2

    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap_servers" \
        --create --if-not-exists --topic "$topic" --partitions 2 --replication-factor 1 \
        --config cleanup.policy=delete --config "retention.ms=$retention_ms"

    partition_count=$(/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap_servers" \
        --describe --topic "$topic" | awk '{for (i = 1; i <= NF; i++) if ($i == "PartitionCount:") print $(i + 1)}')

    case "$partition_count" in
        1)
            /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap_servers" \
                --alter --topic "$topic" --partitions 2
            ;;
        2)
            ;;
        *)
            echo "Unexpected partition count for $topic: $partition_count" >&2
            exit 1
            ;;
    esac

    /opt/kafka/bin/kafka-configs.sh --bootstrap-server "$bootstrap_servers" \
        --alter --entity-type topics --entity-name "$topic" \
        --add-config "cleanup.policy=delete,retention.ms=$retention_ms"
}

while [ "$#" -ge 2 ]; do
    configure_topic "$1" 604800000
    configure_topic "$2" 2592000000
    shift 2
done
