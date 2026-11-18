#!/usr/bin/env python3
"""
Prepara los datos de GeoLotería (se ejecuta en GitHub Actions, que sí tiene internet):

1. geo/src/main/assets/spain.json   → contorno de España (Natural Earth 1:10m), lista de polígonos [[lon,lat],…]
2. geo/src/main/assets/nucleos.csv  → poblaciones de España de GeoNames (cities500: > 500 habitantes)
                                       columnas: nombre;lat;lon;poblacion;provincia
"""
import csv
import io
import json
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "geo" / "src" / "main" / "assets"
NE_URL = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson"
GN_URL = "https://download.geonames.org/export/dump/cities500.zip"
GN_ADMIN1 = "https://download.geonames.org/export/dump/admin1CodesASCII.txt"


def fetch(url: str) -> bytes:
    last = None
    for i in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "geoloteria-data/1.0"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"  reintento {i + 1}: {e}", flush=True)
            time.sleep(5 * (i + 1))
    raise RuntimeError(f"No se pudo descargar {url}: {last}")


def simplify(ring, tol):
    """Douglas-Peucker sencillo para aligerar el contorno."""
    if len(ring) < 4:
        return ring

    def perp(p, a, b):
        (x, y), (x1, y1), (x2, y2) = p, a, b
        dx, dy = x2 - x1, y2 - y1
        if dx == dy == 0:
            return ((x - x1) ** 2 + (y - y1) ** 2) ** 0.5
        return abs(dy * x - dx * y + x2 * y1 - y2 * x1) / (dx * dx + dy * dy) ** 0.5

    def dp(pts):
        if len(pts) < 3:
            return pts
        idx, dmax = 0, 0.0
        for i in range(1, len(pts) - 1):
            d = perp(pts[i], pts[0], pts[-1])
            if d > dmax:
                idx, dmax = i, d
        if dmax > tol:
            return dp(pts[: idx + 1])[:-1] + dp(pts[idx:])
        return [pts[0], pts[-1]]

    out = dp(ring)
    return out if len(out) >= 4 else ring


def build_spain():
    print("Descargando contorno (Natural Earth)…", flush=True)
    gj = json.loads(fetch(NE_URL))
    feat = next(f for f in gj["features"] if f["properties"].get("ADM0_A3") == "ESP")
    geom = feat["geometry"]
    polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
    rings = []
    for poly in polys:
        outer = poly[0]
        s = simplify([(round(x, 4), round(y, 4)) for x, y in outer], 0.004)
        if len(s) >= 4:
            rings.append([[x, y] for x, y in s])
    (ASSETS / "spain.json").write_text(json.dumps(rings, separators=(",", ":")))
    print(f"  {len(rings)} polígonos, {sum(len(r) for r in rings)} vértices")


def build_nucleos():
    print("Descargando poblaciones (GeoNames)…", flush=True)
    admin1 = {}
    for line in fetch(GN_ADMIN1).decode("utf-8").splitlines():
        p = line.split("\t")
        if len(p) >= 2 and p[0].startswith("ES."):
            admin1[p[0][3:]] = p[1]
    z = zipfile.ZipFile(io.BytesIO(fetch(GN_URL)))
    rows = []
    with z.open("cities500.txt") as f:
        for line in io.TextIOWrapper(f, encoding="utf-8"):
            p = line.rstrip("\n").split("\t")
            if len(p) < 15 or p[8] != "ES" or p[6] != "P":
                continue
            pop = int(p[14] or 0)
            if pop < 500:
                continue
            rows.append((p[1], round(float(p[4]), 5), round(float(p[5]), 5), pop, admin1.get(p[10], "")))
    rows.sort(key=lambda r: -r[3])
    buf = io.StringIO()
    w = csv.writer(buf, delimiter=";", lineterminator="\n")
    for r in rows:
        w.writerow(r)
    (ASSETS / "nucleos.csv").write_text(buf.getvalue(), encoding="utf-8")
    print(f"  {len(rows)} núcleos")


if __name__ == "__main__":
    ASSETS.mkdir(parents=True, exist_ok=True)
    build_spain()
    build_nucleos()
    sys.exit(0)
