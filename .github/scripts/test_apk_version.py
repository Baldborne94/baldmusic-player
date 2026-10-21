"""Test di apk_version.py: le novità sono i titoli delle PR, anche lanciato da android/ come in CI.

    python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "apk_version.py")


def run(cwd: str, *args: str) -> str:
    return subprocess.run(list(args), cwd=cwd, check=True, capture_output=True, text=True).stdout


class ApkVersionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        git = lambda *a: run(self.repo, "git", "-c", "user.name=t", "-c", "user.email=t@t", *a)
        self.git = git
        git("init", "-q", "-b", "main")
        os.makedirs(os.path.join(self.repo, "android"))
        os.makedirs(os.path.join(self.repo, "web"))
        self.write("android/a.txt", "1")
        git("add", ".")
        git("commit", "-q", "-m", "Inizio")
        self.first = git("rev-parse", "HEAD").strip()
        # Una PR che tocca android/, unita con un merge come fa GitHub.
        self.pr("android/a.txt", "2", 1, "Timer da Android Auto")
        # Una PR che tocca solo il sito: non va nelle novità dell'APK.
        self.pr("web/b.txt", "x", 2, "Solo il sito")
        self.pr("android/a.txt", "3", 3, "Segnala un problema")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write(self, path: str, text: str) -> None:
        with open(os.path.join(self.repo, path), "w") as f:
            f.write(text)

    def pr(self, path: str, text: str, number: int, title: str) -> None:
        self.git("checkout", "-q", "-b", f"pr{number}")
        self.write(path, text)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", f"lavoro della PR {number}")
        self.git("checkout", "-q", "main")
        self.git("merge", "-q", "--no-ff", f"pr{number}", "-m", f"Merge pull request #{number} from me/pr{number}\n\n{title}")

    def changes(self, cwd: str) -> list[str]:
        prev = os.path.join(self.repo, "prev.json")
        with open(prev, "w") as f:
            json.dump({"sha": self.first, "history": []}, f)
        apk = os.path.join(self.repo, "x.apk")
        with open(apk, "wb") as f:
            f.write(b"apk")
        out = os.path.join(self.repo, "version.json")
        run(cwd, sys.executable, SCRIPT, "--code", "7", "--sha", "abc", "--apk", apk, "--prev", prev, "--out", out)
        with open(out) as f:
            return json.load(f)["history"][0]["changes"]

    def test_lanciato_da_android_come_in_ci(self) -> None:
        # Prima: da android/ cercava android/android e dava sempre «Correzioni e piccoli miglioramenti».
        self.assertEqual(["Segnala un problema", "Timer da Android Auto"], self.changes(os.path.join(self.repo, "android")))

    def test_lanciato_dalla_radice(self) -> None:
        self.assertEqual(["Segnala un problema", "Timer da Android Auto"], self.changes(self.repo))


if __name__ == "__main__":
    unittest.main()
