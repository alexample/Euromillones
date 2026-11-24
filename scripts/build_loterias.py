"""Premios mayores (1º, 2º, 3º y 4º) del Sorteo de Navidad y del Niño desde la API de SELAE.
Para los años que la API no tiene, se añaden los Gordos de Navidad conocidos (data/navidad.json)."""
import json, time, urllib.request

H = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
     "Accept": "application/json, text/plain, */*",
     "Referer": "https://www.loteriasyapuestas.es/es/resultados/loteria-nacional"}

def get(a, b):
    u = ("https://www.loteriasyapuestas.es/servicios/buscadorSorteos?game_id=LNAC&celebrados=true"
         f"&fechaInicioInclusiva={a}&fechaFinInclusiva={b}")
    for intento in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=40) as r:
                d = json.loads(r.read().decode("utf-8"))
                return d if isinstance(d, list) else []
        except Exception as e:
            print("reintento", a, e); time.sleep(3)
    return []

def nums(x):
    if not x: return []
    if isinstance(x, dict): x = [x]
    return [str(p.get("decimo", "")).zfill(5) for p in x if str(p.get("decimo", "")).isdigit()]

out = []
for y in range(1960, 2027):
    for sorteo, a, b, clave in (("Navidad", f"{y}1218", f"{y}1231", "NAVIDAD"), ("Niño", f"{y}0101", f"{y}0108", "NI")):
        for d in get(a, b):
            n = d.get("nombre", "").upper()
            if "EXTRAORDINARIO" not in n or clave not in n: continue
            pr = {"1": nums(d.get("primerPremio")), "2": nums(d.get("segundoPremio")),
                  "3": nums(d.get("tercerosPremios")), "4": nums(d.get("cuartosPremios"))}
            if not pr["1"]: continue
            out.append({"sorteo": sorteo, "anyo": y, "fecha": d["fecha_sorteo"][:10], "premios": {k: v for k, v in pr.items() if v}})
            print(sorteo, y, pr)
        time.sleep(0.5)

tiene = {o["anyo"] for o in out if o["sorteo"] == "Navidad"}
for g in json.load(open("data/navidad.json")):
    if g["year"] not in tiene:
        out.append({"sorteo": "Navidad", "anyo": g["year"], "fecha": f"{g['year']}-12-22", "premios": {"1": [g["number"].zfill(5)]}})
out.sort(key=lambda o: (o["fecha"]), reverse=True)
txt = json.dumps(out, ensure_ascii=False, separators=(",", ":"))
open("data/loterias.json", "w").write(txt)
open("geo/src/main/assets/loterias.json", "w").write(txt)
print("total", len(out))
