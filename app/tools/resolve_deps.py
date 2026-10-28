#!/usr/bin/env python3
"""Minimal Maven runtime-dependency resolver (Google Maven + Maven Central only).
Usage: resolve_deps.py <outdir> group:artifact:version ...  -> downloads .aar/.jar, prints resolved list."""
import sys, os, re, urllib.request, xml.etree.ElementTree as ET
REPOS = ["https://dl.google.com/dl/android/maven2", "https://repo1.maven.org/maven2"]
NS = "{http://maven.apache.org/POM/4.0.0}"
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
def fetch(path):
    for r in REPOS:
        try:
            with urllib.request.urlopen(f"{r}/{path}", timeout=30) as f: return f.read()
        except Exception: pass
    return None
def vcmp(v): return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]
resolved = {}
queue = [tuple(a.split(":")) for a in sys.argv[2:]]
while queue:
    g, a, v = queue.pop(0)
    v = v.strip("[]").split(",")[0]
    key = (g, a)
    if key in resolved:
        try:
            if vcmp(resolved[key]) >= vcmp(v): continue
        except TypeError: continue
    resolved[key] = v
    base = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}"
    pom = fetch(base + ".pom")
    if pom is None: print("MISSING POM", g, a, v, file=sys.stderr); continue
    root = ET.fromstring(pom)
    props = {p.tag.replace(NS, ""): (p.text or "") for p in root.findall(f"{NS}properties/*")}
    props["project.version"] = v
    for d in root.findall(f"{NS}dependencies/{NS}dependency"):
        get = lambda t: (d.findtext(NS + t) or "").strip()
        scope = get("scope") or "compile"; opt = get("optional") == "true"
        if scope not in ("compile", "runtime") or opt: continue
        dv = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), get("version"))
        if not dv or "$" in dv: print("SKIP unversioned", get("groupId"), get("artifactId"), file=sys.stderr); continue
        queue.append((get("groupId"), get("artifactId"), dv))
for (g, a), v in sorted(resolved.items()):
    base = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}"
    for ext in ("aar", "jar"):
        fn = os.path.join(out, f"{g}__{a}-{v}.{ext}")
        if os.path.exists(fn): print(fn); break
        data = fetch(f"{base}.{ext}")
        if data: open(fn, "wb").write(data); print(fn); break
    else: print("NO ARTIFACT", g, a, v, file=sys.stderr)
