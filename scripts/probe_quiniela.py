"""Prueba: API de SELAE para La Quiniela (LAQU) y CSV de football-data."""
import json, os, urllib.request
os.makedirs("data/raw_quiniela", exist_ok=True)
H = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
     "Accept": "application/json, text/plain, */*", "Referer": "https://www.loteriasyapuestas.es/es/resultados/quiniela"}
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=40) as r:
        return r.read().decode("utf-8", "replace")
base = "https://www.loteriasyapuestas.es/servicios/buscadorSorteos?game_id=LAQU&celebrados={c}&fechaInicioInclusiva={a}&fechaFinInclusiva={b}"
for name, u in [("hist_2025", base.format(c="true", a="20251101", b="20251130")),
                ("hist_1999", base.format(c="true", a="19991001", b="19991031")),
                ("proximos", base.format(c="false", a="20261120", b="20261231"))]:
    try:
        t = get(u); open(f"data/raw_quiniela/{name}.json", "w").write(t); print(name, len(t), t[:400])
    except Exception as e: print(name, "ERROR", e)
for u in ["https://www.football-data.co.uk/mmz4281/2526/SP1.csv", "https://www.football-data.co.uk/mmz4281/0001/SP2.csv",
          "https://www.football-data.co.uk/mmz4281/2627/SP1.csv"]:
    try:
        t = get(u); print(u, len(t), t[:300].replace("\n", " | "))
        open("data/raw_quiniela/" + u.split("/")[-2] + u.split("/")[-1], "w").write(t)
    except Exception as e: print(u, "ERROR", e)
