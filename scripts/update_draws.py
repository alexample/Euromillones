#!/usr/bin/env python3
"""
Actualiza data/draws.json con todos los sorteos de Euromillones.

Fuente: páginas de histórico de https://www.euro-millions.com/results-history-AAAA
- Si data/draws.json no existe o está vacío: descarga todos los años (2004 → hoy).
- Si existe: descarga solo el año en curso (y el anterior en enero).
Uso: python3 scripts/update_draws.py [--full]
"""
import json
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "data" / "draws.json"
URL = "https://www.euro-millions.com/results-history-{year}"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"
FIRST_YEAR = 2004
# Fuente de respaldo (últimos sorteos, XML de la Lotería Nacional británica)
FALLBACK_URL = "https://www.national-lottery.co.uk/results/euromillions/draw-history/csv"

ROW_SPLIT = re.compile(r'<tr class="resultRow"')
DATE_RE = re.compile(r'href="/results/(\d{2})-(\d{2})-(\d{4})"')
BALL_RE = re.compile(r'class="resultBall ball[^"]*">\s*(\d+)\s*<')
STAR_RE = re.compile(r'class="resultBall lucky-star[^"]*">\s*(\d+)\s*<')
JACKPOT_RE = re.compile(r'data-title="Jackpot">\s*<strong[^>]*>([^<]+)<', re.S)


def fetch(url: str, attempts: int = 4) -> str:
    last = None
    for i in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "en"})
            with urllib.request.urlopen(req, timeout=40) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            last = e
            wait = 5 * (i + 1)
            print(f"  reintento {i + 1}/{attempts} en {wait}s ({e})", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"No se pudo descargar {url}: {last}")


def parse(html: str) -> list[dict]:
    draws = []
    for chunk in ROW_SPLIT.split(html)[1:]:
        chunk = chunk.split("</tr>", 1)[0]
        m = DATE_RE.search(chunk)
        balls = [int(x) for x in BALL_RE.findall(chunk)]
        stars = [int(x) for x in STAR_RE.findall(chunk)]
        if not m or len(balls) != 5 or len(stars) != 2:
            continue
        d, mo, y = m.groups()
        jp = JACKPOT_RE.search(chunk)
        jackpot = jp.group(1).replace("&euro;", "€").strip() if jp else None
        draws.append({
            "date": f"{y}-{mo}-{d}",
            "numbers": sorted(balls),
            "stars": sorted(stars),
            "jackpot": jackpot,
        })
    return draws


def parse_fallback(xml_text: str) -> list[dict]:
    """Lee el XML de la Lotería Nacional británica: cada <game> es un sorteo."""
    root = ET.fromstring(xml_text)
    out = []
    for game in root.iter("game"):
        d = game.findtext(".//draw-date")
        balls = sorted(int(b.text) for b in game.findall(".//balls/ball") if b.text)
        stars = sorted(int(b.text) for b in game.findall(".//balls/bonus-ball") if b.text and b.get("type") == "luckystar")
        if not d or len(balls) != 5 or len(stars) != 2:
            continue
        out.append({"date": d[:10], "numbers": balls, "stars": stars, "jackpot": None})
    return out


def fetch_year(year: int, current: int) -> list[dict]:
    """Año desde euro-millions.com; si falla el año en curso, se usa la fuente de respaldo."""
    try:
        return parse(fetch(URL.format(year=year)))
    except Exception as e:  # noqa: BLE001
        if year != current:
            raise
        print(f"  euro-millions.com no responde ({e}); usando la fuente de respaldo…", flush=True)
        found = parse_fallback(fetch(FALLBACK_URL))
        print(f"  respaldo: {len(found)} sorteos recientes", flush=True)
        return found


def main() -> int:
    if "--probar-respaldo" in sys.argv:
        found = parse_fallback(fetch(FALLBACK_URL))
        print(f"Respaldo OK: {len(found)} sorteos, el último {found[0] if found else '—'}")
        return 0 if found else 1

    full = "--full" in sys.argv
    existing: dict[str, dict] = {}
    if OUT.exists() and not full:
        try:
            existing = {d["date"]: d for d in json.loads(OUT.read_text(encoding="utf-8"))}
        except Exception:  # noqa: BLE001
            existing = {}

    today = date.today()
    if existing:
        start = max(int(k[:4]) for k in existing)
        if today.month == 1:
            start = min(start, today.year - 1)
    else:
        start = FIRST_YEAR

    for year in range(start, today.year + 1):
        print(f"Año {year}…", flush=True)
        found = fetch_year(year, today.year)
        print(f"  {len(found)} sorteos", flush=True)
        if not found and year < today.year:
            raise RuntimeError(f"El año {year} no devolvió sorteos: ¿ha cambiado la web?")
        for d in found:
            old = existing.get(d["date"])
            if old and not d.get("jackpot"):
                d["jackpot"] = old.get("jackpot")
            existing[d["date"]] = d
        time.sleep(1)

    draws = sorted(existing.values(), key=lambda d: d["date"])
    for i, d in enumerate(draws, start=1):
        d["draw_id"] = i  # el sorteo nº1 fue el 13/02/2004

    # El más reciente primero (lo que la app muestra arriba)
    draws.reverse()
    ordered = [
        {"draw_id": d["draw_id"], "date": d["date"], "numbers": d["numbers"],
         "stars": d["stars"], "jackpot": d.get("jackpot")}
        for d in draws
    ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(ordered, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Guardados {len(ordered)} sorteos. Último: {ordered[0]['date']} (nº {ordered[0]['draw_id']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
