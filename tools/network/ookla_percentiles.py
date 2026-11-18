"""Test-weighted percentiles of India upload speed and latency from Ookla open-data tiles.

Input: the Ookla parquet tile files (CC BY-NC-SA 4.0, s3://ookla-open-data) for one quarter.
Usage: python -I tools/network/ookla_percentiles.py mobile.parquet fixed.parquet
Tiles are kept when their first polygon vertex lies in a crude India bounding box
(lon 68.1-97.4, lat 6.7-35.6); the box also catches parts of neighbouring countries.
Each tile counts with weight = its number of tests; the value is the tile's average,
so these are percentiles of tile averages, not of individual tests.
"""
import sys
import duckdb

PT = r"POLYGON\(\((-?[0-9.]+) (-?[0-9.]+)"


def run(path: str) -> tuple:
    con = duckdb.connect()
    base = f"""
      with f as (
        select avg_u_kbps / 1000.0 u, avg_lat_ms l, tests,
               regexp_extract(tile, '{PT}', 1)::double lon,
               regexp_extract(tile, '{PT}', 2)::double lat
        from read_parquet(?)
      ),
      i as (select * from f where lon between 68.1 and 97.4 and lat between 6.7 and 35.6),
      wu as (select u, sum(tests) over (order by u) * 1.0 / sum(tests) over () c from i),
      wl as (select l, sum(tests) over (order by l) * 1.0 / sum(tests) over () c from i)
      select (select count(*) from i), (select sum(tests) from i),
        (select min(u) from wu where c >= 0.2), (select min(u) from wu where c >= 0.5), (select min(u) from wu where c >= 0.8),
        (select min(l) from wl where c >= 0.2), (select min(l) from wl where c >= 0.5), (select min(l) from wl where c >= 0.8)
    """
    return con.execute(base, [path]).fetchone()


if __name__ == "__main__":
    print("file,tiles,tests,up_p20,up_p50,up_p80,lat_p20,lat_p50,lat_p80")
    for p in sys.argv[1:]:
        print(p, *run(p), sep=",")
