# Network profiles for the load runs

D8 records that there is no student pilot. These profiles come from published Indian
speed measurements. Raw rows, with source links and dates, are in
[`results/network/public-profiles.csv`](../results/network/public-profiles.csv).

## What was found

| Source | Segment | Upload | Latency | Period |
| --- | --- | --- | --- | --- |
| [Ookla Speedtest Global Index](https://www.speedtest.net/global-index/india), country median | mobile | 9.82 Mbps | 26 ms | Sep 2026 |
| same | fixed broadband | 57.90 Mbps | 7 ms | Sep 2026 |
| [Ookla open data](https://registry.opendata.aws/speedtest-global-performance/) tiles, p20 / p50 / p80 | mobile | 7.12 / 13.68 / 21.25 Mbps | 22 / 29 / 41 ms | 2026 Q3 |
| same | fixed broadband | 18.54 / 54.41 / 107.41 Mbps | 5 / 9 / 20 ms | 2026 Q3 |

The tile percentiles come from [`tools/network/ookla_percentiles.py`](../tools/network/ookla_percentiles.py)
run on Ookla's quarterly parquet files (CC BY-NC-SA 4.0; 560,673 mobile tiles, 453,699
fixed tiles). Each tile is weighted by its test count. Limits: the values are percentiles
of tile averages, not of single tests, so the spread between users is understated; and the
bounding box used to select India also catches border areas of neighbouring countries.

## What could not be obtained

- TRAI MySpeed: the portal publishes no average speeds. The April to June 2026
  [performance indicators report](https://www.trai.gov.in/sites/default/files/2026-10/QPIR_12102026.pdf)
  was searched as text; it holds compliance tables for tariff-offered speed, not measured
  upload speeds. No TRAI upload figure is used.
- Opensignal's India page returned HTTP 403, so no Opensignal figure is used.

## Cohorts

Upload is the direction that matters: the file goes up. Loss is chosen so the Mathis
limit, rate = 1.22 x MSS / (RTT x sqrt(loss)) with MSS = 1460 bytes, stays above the
shaped rate. If it did not, TCP, not the shaper, would set the speed.

| Cohort | Upload rate | RTT | Loss | Mathis limit | Headroom | Taken from |
| --- | --- | --- | --- | --- | --- | --- |
| slow | 7 Mbps | 40 ms | 0.1% | 11.3 Mbps | 1.6x | mobile upload p20; mobile latency p80 (41 ms) |
| medium | 20 Mbps | 25 ms | 0.05% | 25.5 Mbps | 1.3x | mobile upload p80 (21.2) and fixed p20 (18.5); latency between mobile p50 and fixed p80 |
| fast | 54 Mbps | 10 ms | 0.01% | 142.5 Mbps | 2.6x | fixed upload p50 (54.4); fixed latency p50 (9 ms) |

The loss values are assumptions; the public data has none. They only have to keep the
limit above the rate. The click-time curve (when students press submit relative to the
deadline) is also a stated assumption, tested at 0.5x and 2x (D8).

## Optional self-measurement

If the author wants one or two real points, submit a test file from a hostel or home
network, tick the optional consent on the receipt page, and read the `upload_mbps`,
`size_bytes` and `network_type` columns of `telemetry_events` for that assignment (the
service stores them under an anonymous id; see `telemetry/TelemetryService.java`). Add the
result as a new row in `results/network/public-profiles.csv` with source `self-measurement`,
the network type and the date. Do not use it to replace the published values: one or two
points are an anchor for the cohorts, not a sample.
