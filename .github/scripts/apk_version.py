#!/usr/bin/env python3
"""version.json per gli aggiornamenti dall'app (AppUpdate in :core).

Le novità sono i titoli delle PR unite in main che toccano android/ dall'APK
pubblicato prima (il suo commit sta nel version.json precedente). Si tengono
le ultime versioni, così chi salta qualche aggiornamento vede tutto quello
che è cambiato da quella che ha.
"""
import argparse
import datetime
import json
import os
import subprocess

HISTORY = 30
FALLBACK = "Correzioni e piccoli miglioramenti"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def is_ancestor(sha: str) -> bool:
    return subprocess.run(["git", "merge-base", "--is-ancestor", sha, "HEAD"], capture_output=True).returncode == 0


def changes(prev_sha: str | None) -> list[str]:
    # Primo APK con le novità, o commit precedente sparito: solo l'ultima PR.
    span = f"{prev_sha}..HEAD" if prev_sha and is_ancestor(prev_sha) else "HEAD~1..HEAD"
    # ":(top)android": la cartella android/ della radice del repository, da qualunque cartella si
    # lanci lo script (la CI lo lancia da android/, dove "android" sarebbe android/android).
    out = git("log", "--first-parent", "--format=%P%x1f%s%x1f%b%x1e", span, "--", ":(top)android")
    titles: list[str] = []
    for record in out.split("\x1e"):
        if not record.strip():
            continue
        parents, subject, body = (record.strip("\n").split("\x1f") + ["", ""])[:3]
        if len(parents.split()) > 1:
            # "Merge pull request #N from …": il titolo della PR è la prima riga del corpo.
            title = next((line.strip() for line in body.splitlines() if line.strip()), "")
        else:
            title = subject.strip()
        if title and not title.startswith("Merge ") and title not in titles:
            titles.append(title)
    return titles or [FALLBACK]


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--code", type=int, required=True)
    p.add_argument("--sha", required=True)
    p.add_argument("--apk", required=True)
    p.add_argument("--prev", help="version.json dell'APK di prima, se c'è")
    p.add_argument("--out", required=True)
    p.add_argument("--notes", help="note della release in Markdown")
    a = p.parse_args()

    prev = {}
    if a.prev and os.path.exists(a.prev):
        with open(a.prev, encoding="utf-8") as f:
            prev = json.load(f)
    today = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    entry = {"versionCode": a.code, "date": today, "changes": changes(prev.get("sha"))}
    history = [entry] + [h for h in prev.get("history", []) if h.get("versionCode") != a.code]
    info = {
        "versionCode": a.code,
        "versionName": f"0.1.{a.code}",
        "date": today,
        "sha": a.sha,
        "apk": os.path.basename(a.apk),
        "size": os.path.getsize(a.apk),
        "history": history[:HISTORY],
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(info, f, ensure_ascii=False, indent=2)

    if a.notes:
        lines = [f"**Versione {info['versionName']}** del {datetime.date.fromisoformat(today).strftime('%d/%m/%Y')} (commit {a.sha[:7]}).", "", "Novità:"]
        lines += [f"- {c}" for c in entry["changes"]]
        lines += [
            "",
            "Dalla versione con «Aggiornamenti» in Impostazioni l'app si aggiorna da sola. Altrimenti scarica `baldmusic.apk` qui sotto dal telefono e aprilo: Android chiederà di consentire l'installazione da questa fonte.",
            "",
            "Richiede Android 8.0 o superiore.",
        ]
        with open(a.notes, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
