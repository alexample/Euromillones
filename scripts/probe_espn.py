import json, urllib.request
H={"User-Agent":"Mozilla/5.0"}
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=40) as r: return json.loads(r.read())
for lg in ("esp.1","esp.2"):
    try:
        t=get(f"https://site.api.espn.com/apis/site/v2/sports/soccer/{lg}/teams")
        teams=t["sports"][0]["leagues"][0]["teams"]; print(lg, len(teams), [(x["team"]["id"],x["team"]["displayName"]) for x in teams[:4]])
        tid=teams[0]["team"]["id"]
        r=get(f"https://site.api.espn.com/apis/site/v2/sports/soccer/{lg}/teams/{tid}/roster")
        a=r.get("athletes",[]); print("roster", len(a)); 
        if a: 
            x=a[0]; print(json.dumps({k:x[k] for k in x if k in ("displayName","dateOfBirth","age","position","statistics")})[:1500])
    except Exception as e: print(lg,"ERROR",e)
try:
    s=get("https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard")
    ev=s["events"][0]; print("evento", ev["id"], ev["name"], ev["date"])
    sm=get(f"https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/summary?event={ev['id']}")
    ro=sm.get("rosters",[]); print("rosters en resumen:", len(ro)); 
    if ro: print(json.dumps(ro[0]["roster"][0])[:800])
except Exception as e: print("scoreboard ERROR", e)
