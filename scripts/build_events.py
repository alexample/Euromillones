#!/usr/bin/env python3
"""
Construye geo/src/main/assets/eventos.tsv con todos los eventos históricos de la Wikipedia en español
(feed "Un día como hoy": acontecimientos, destacados, nacimientos y fallecimientos de los 366 días del año).

Cada línea:  fecha(aaaa-mm-dd, años antiguos con signo) \t tipo \t texto \t título del artículo de Wikipedia
  tipo: A = acontecimiento, N = nacimiento, M = fallecimiento
El enlace es https://es.wikipedia.org/wiki/<título>.
"""
import hashlib
import json
import time
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "geo" / "src" / "main" / "assets" / "eventos.tsv"
URL = "https://es.wikipedia.org/api/rest_v1/feed/onthisday/all/{m:02d}/{d:02d}"
UA = "GeoLoteria/1.0 (https://github.com/alexample/Euromillones)"
DAYS = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]


def fetch(url: str):
    last = None
    for i in range(6):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read())
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(3 * (i + 1))
    raise RuntimeError(f"{url}: {last}")


def clean(s: str) -> str:
    return " ".join((s or "").replace("\t", " ").split())


def main():
    rows = {}
    for m in range(1, 13):
        for d in range(1, DAYS[m - 1] + 1):
            data = fetch(URL.format(m=m, d=d))
            for kind, tag, prefix in (("selected", "A", ""), ("events", "A", ""), ("births", "N", "Nace "), ("deaths", "M", "Muere ")):
                for e in data.get(kind, []) or []:
                    y = e.get("year")
                    text = clean(e.get("text"))
                    pages = e.get("pages") or []
                    if y is None or not text or not pages:
                        continue
                    title = pages[0].get("title") or ""
                    if not title:
                        continue
                    if prefix:
                        text = prefix + text
                    else:
                        text = text[0].upper() + text[1:]
                    y = int(y)
                    date = (f"{y:04d}" if y >= 0 else f"-{-y:04d}") + f"-{m:02d}-{d:02d}"
                    rows[(date, text)] = (date, tag, text, title)
            time.sleep(0.15)
        print(f"mes {m}: {len(rows)} eventos acumulados", flush=True)

    def key(r):
        y = int(r[0][:-6])
        return (y, r[0][-5:], r[1], r[2])

    out = sorted(rows.values(), key=key)
    body = "\n".join("\t".join(r) for r in out) + "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(body, encoding="utf-8")
    print(len(out), "eventos;", "acontecimientos", sum(r[1] == "A" for r in out),
          "nacimientos", sum(r[1] == "N" for r in out), "fallecimientos", sum(r[1] == "M" for r in out))
    print("años de", out[0][0], "a", out[-1][0], "· SHA-256", hashlib.sha256(body.encode()).hexdigest())


if __name__ == "__main__":
    main()
