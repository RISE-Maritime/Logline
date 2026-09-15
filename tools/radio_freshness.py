#!/usr/bin/env python3
"""How fresh are the radio subjects in a Logline recording?

    pip install mcap mcap-protobuf-support
    python3 tools/radio_freshness.py logline-2026-09-14.mcap

For every `radio_*` channel it reports:

- messages: how many were written (the poll rate x run length);
- distinct ts: distinct payload timestamps. After 0d14ce1 a held reading keeps one timestamp, so this
  should be close to `changes`, i.e. the number of real modem reports, not the message count;
- changes: how many consecutive messages carry a different value than the one before;
- lag: log time minus payload time, as median / p90 / max seconds. That is how old the published
  value was when it was written.

A large recording is scanned end to end (the writer emits no chunk index), about a minute per GB.
"""

import statistics
import sys
from collections import defaultdict

from mcap.reader import make_reader
from mcap_protobuf.decoder import DecoderFactory


def main(path: str) -> None:
    messages = defaultdict(int)
    stamps = defaultdict(set)
    changes = defaultdict(int)
    last_value = {}
    lags = defaultdict(list)

    with open(path, "rb") as f:
        reader = make_reader(f, decoder_factories=[DecoderFactory()])
        for _, channel, message, proto in reader.iter_decoded_messages(
            topics=None, log_time_order=False
        ):
            topic = channel.topic
            if "/pubsub/radio_" not in topic:
                continue
            payload_ns = proto.timestamp.seconds * 1_000_000_000 + proto.timestamp.nanos
            messages[topic] += 1
            stamps[topic].add(payload_ns)
            if last_value.get(topic, object()) != proto.value:
                changes[topic] += 1
            last_value[topic] = proto.value
            lags[topic].append((message.log_time - payload_ns) / 1e9)

    if not messages:
        print("no radio_* channels in this recording")
        return

    header = f"{'channel':<52} {'messages':>9} {'distinct ts':>11} {'changes':>8}  lag s (median / p90 / max)"
    print(header)
    print("-" * len(header))
    for topic in sorted(messages):
        lag = sorted(lags[topic])
        p90 = lag[min(len(lag) - 1, int(len(lag) * 0.9))]
        name = topic.split("/pubsub/", 1)[1]
        print(
            f"{name:<52} {messages[topic]:>9} {len(stamps[topic]):>11} {changes[topic]:>8}  "
            f"{statistics.median(lag):.1f} / {p90:.1f} / {lag[-1]:.1f}"
        )


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
