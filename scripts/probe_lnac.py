"""Prueba: descarga los sorteos de Navidad y del Niño de la API de SELAE y guarda la respuesta en bruto."""
import json, os, time, urllib.request
os.makedirs("data/raw_lnac", exist_ok=True)
H = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
     "Accept": "application/json, text/plain, */*", "Referer": "https://www.loteriasyapuestas.es/es/resultados/loteria-nacional"}
def get(a, b):
    u = ("https://www.loteriasyapuestas.es/servicios/buscadorSorteos?game_id=LNAC&celebrados=true"
         f"&fechaInicioInclusiva={a}&fechaFinInclusiva={b}")
    with urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=40) as r:
        return r.read().decode("utf-8")
for y in (2025, 2024, 1990):
    for name, a, b in (("navidad", f"{y}1220", f"{y}1223"), ("nino", f"{y}0104", f"{y}0107")):
        try:
            t = get(a, b)
            open(f"data/raw_lnac/{name}_{y}.json", "w").write(t)
            print(name, y, len(t), t[:300])
        except Exception as e:
            print(name, y, "ERROR", e)
        time.sleep(1)
