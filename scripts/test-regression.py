#!/usr/bin/env python3
"""Run dependency-free Java regression tests; no installs or device required.

Uses the installed JDK and compiles Android-independent production classes plus
all tests/java sources. Temporary outputs stay outside the repository. Android
compilation and APK verification are separate: scripts/build-manual.sh --release.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java'
TESTS = ROOT / 'tests/java'


def main():
    java_home = os.environ.get('JAVA_HOME')
    if not java_home and Path('/usr/libexec/java_home').exists():
        java_home = subprocess.check_output(['/usr/libexec/java_home'], text=True).strip()
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not javac or not java:
        raise SystemExit('An installed JDK is required; nothing will be installed automatically.')
    sources = [p for p in SOURCE.rglob('*.java')
               if not p.name.startswith('.')
               and not re.search(r'^import\s+android\.', p.read_text(encoding='utf-8'), re.MULTILINE)]
    tests = sorted(p for p in TESTS.rglob('*.java') if not p.name.startswith('.'))
    mains = []
    for p in tests:
        text = p.read_text()
        if re.search(r'public\s+static\s+void\s+main\s*\(', text):
            package = re.search(r'^package\s+([\w.]+)\s*;', text, re.MULTILINE)
            mains.append((package.group(1) + '.' if package else '') + p.stem)
    if not mains:
        raise SystemExit('No executable regression tests found.')
    scratch = Path.home() / '.hermes/cache/scratch'
    # Honor a caller-provided temporary location; never use a repo build directory.
    temp_root = os.environ.get('TVGUARD_TEST_TMPDIR')
    if temp_root:
        scratch = Path(temp_root)
    elif not scratch.is_dir():
        scratch = Path(tempfile.gettempdir())
    with tempfile.TemporaryDirectory(prefix='tvguard-regression-', dir=scratch) as tmp:
        print(f'Compiling {len(sources)} production classes and {len(tests)} test sources.', flush=True)
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', tmp]
                       + [str(p) for p in sources + tests], check=True)
        for test in mains:
            print(f'RUN {test}', flush=True)
            subprocess.run([java, '-ea', '-cp', tmp, test], check=True)
    print(f'PASS: {len(mains)} regression suites.', flush=True)


if __name__ == '__main__':
    main()
