"""Datos para la app Quinielas.
 - data/quinielas.json : todas las quinielas jugadas (SELAE, desde 1998): fecha, jornada, 14 signos, pleno y partidos.
 - data/ligas.json     : resultados de 1ª y 2ª desde 2000/01 (football-data.co.uk).
 - data/jornada.json   : próximas quinielas con sus partidos (y resultados si ya se han jugado).
 - data/equipos.json   : equivalencia nombre SELAE -> nombre football-data (se deduce cruzando fecha y marcador).
"""
import csv, io, json, re, time, unicodedata, datetime, urllib.request, collections

H = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
     "Accept": "application/json, text/plain, */*", "Referer": "https://www.loteriasyapuestas.es/es/resultados/quiniela"}

def get(u, tries=3):
    for k in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=60) as r:
                return r.read().decode("utf-8-sig", "replace")
        except Exception as e:
            print("reintento", u[:90], e); time.sleep(3)
    return None

def limpia(n):
    if n is None: return None
    # «(m)» = fútbol masculino (se quita); «(f)» = femenino (se deja: no hay que confundirlo con el equipo de 1ª)
    return re.sub(r"\s*\(m\)\s*$", "", n.strip())

API = ("https://www.loteriasyapuestas.es/servicios/buscadorSorteos?game_id=LAQU&celebrados={c}"
       "&fechaInicioInclusiva={a}&fechaFinInclusiva={b}")

# ---------------- Histórico de quinielas ----------------
hoy = datetime.date.today()
jornadas = {}
d = datetime.date(1998, 7, 1)
while d <= hoy:
    e = (d.replace(day=28) + datetime.timedelta(days=4)).replace(day=1) - datetime.timedelta(days=1)
    t = get(API.format(c="true", a=d.strftime("%Y%m%d"), b=e.strftime("%Y%m%d")))
    try: arr = json.loads(t) if t else []
    except Exception: arr = []
    for s in arr if isinstance(arr, list) else []:
        comb = s.get("combinacion")
        if not comb: continue
        signos = [x.strip() for x in comb.split("-")]
        if len(signos) < 14: continue
        s14 = "".join(x[:1] for x in signos[:14])
        if not re.fullmatch(r"[1X2]{14}", s14): continue
        partidos = []
        for p in sorted(s.get("partidos") or [], key=lambda p: p.get("posicion") or 0):
            partidos.append([limpia(p.get("local")), limpia(p.get("visitante")), (p.get("marcador") or "").strip(),
                             (p.get("fecha_completa") or "")[:10]])
        jornadas[s["id_sorteo"]] = {
            "f": s["fecha_sorteo"][:10], "n": s.get("numero"), "j": s.get("jornada"), "t": s.get("temporada"),
            "s": s14, "p15": " - ".join(signos[14:]) if len(signos) > 14 else "",
            "e": partidos[:15] if any(x[0] for x in partidos) else [],
        }
    d = e + datetime.timedelta(days=1)
    time.sleep(0.3)
hist = sorted(jornadas.values(), key=lambda x: x["f"])
print("quinielas:", len(hist), hist[0]["f"] if hist else None, hist[-1]["f"] if hist else None)

# ---------------- Ligas (1ª y 2ª) ----------------
ligas = []
y0 = 2000
yN = hoy.year if hoy.month >= 7 else hoy.year - 1
for y in range(y0, yN + 1):
    code = f"{y % 100:02d}{(y + 1) % 100:02d}"
    for div in ("SP1", "SP2"):
        t = get(f"https://www.football-data.co.uk/mmz4281/{code}/{div}.csv")
        if not t: continue
        for r in csv.DictReader(io.StringIO(t)):
            try:
                dt = r.get("Date", "")
                dd, mm, yy = dt.split("/")
                yy = ("20" + yy) if len(yy) == 2 else yy
                ligas.append([f"{yy}-{mm}-{dd}", 1 if div == "SP1" else 2, r["HomeTeam"].strip(), r["AwayTeam"].strip(),
                              int(r["FTHG"]), int(r["FTAG"])])
            except Exception:
                pass
ligas.sort(key=lambda x: x[0])
print("partidos de liga:", len(ligas))

# ---------------- Equivalencia de nombres (por fecha y marcador) ----------------
idx = collections.defaultdict(list)
for m in ligas:
    idx[(m[0], m[4], m[5])].append(m)
votos = collections.defaultdict(collections.Counter)
def dias(f, k):
    return (datetime.date.fromisoformat(f) + datetime.timedelta(days=k)).isoformat()
for q in hist:
    for loc, vis, marc, f in q["e"]:
        if not loc or not marc or not f or "-" not in marc: continue
        try: hg, ag = [int(x) for x in marc.split("-")]
        except Exception: continue
        cands = []
        for k in (0, -1, 1):
            cands += idx.get((dias(f, k), hg, ag), [])
        if len(cands) == 1:
            votos[loc][cands[0][2]] += 1
            votos[vis][cands[0][3]] += 1
equipos = {}
for sel, c in votos.items():
    nombre, n = c.most_common(1)[0]
    if n >= 2 and n >= 0.6 * sum(c.values()):
        equipos[sel] = nombre
print("equipos emparejados:", len(equipos))

# ---------------- Próximas quinielas ----------------
proximas = []
t = get(API.format(c="false", a=(hoy - datetime.timedelta(days=10)).strftime("%Y%m%d"), b=(hoy + datetime.timedelta(days=40)).strftime("%Y%m%d")))
try: arr = json.loads(t) if t else []
except Exception: arr = []
for s in arr if isinstance(arr, list) else []:
    partidos = []
    for p in sorted(s.get("partidos") or [], key=lambda p: p.get("posicion") or 0):
        partidos.append({"pos": p.get("posicion"), "local": limpia(p.get("local")), "visitante": limpia(p.get("visitante")),
                         "signo": (p.get("signo") or "").strip(), "marcador": (p.get("marcador") or "").strip(),
                         "fecha": p.get("fecha_completa") or ""})
    if partidos:
        proximas.append({"f": s["fecha_sorteo"][:10], "n": s.get("numero"), "j": s.get("jornada"), "t": s.get("temporada"),
                         "partidos": partidos})
proximas.sort(key=lambda x: x["f"])
print("próximas:", [(p["n"], p["f"]) for p in proximas])

def dump(path, obj):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(obj, fh, ensure_ascii=False, separators=(",", ":"))
dump("data/quinielas.json", hist)
dump("data/ligas.json", ligas)
dump("data/equipos.json", equipos)
dump("data/jornada.json", {"actualizado": datetime.datetime.utcnow().isoformat(timespec="minutes") + "Z", "proximas": proximas})
# Copia para la app (sin conexión); la app descarga la versión al día
import os
os.makedirs("quinielas/src/main/assets", exist_ok=True)
for f in ("quinielas.json", "ligas.json", "equipos.json", "jornada.json"):
    with open("data/" + f, encoding="utf-8") as a, open("quinielas/src/main/assets/" + f, "w", encoding="utf-8") as b:
        b.write(a.read())
