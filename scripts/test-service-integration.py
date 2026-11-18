#!/usr/bin/env python3
"""Run actual GuardService/Prefs/PinStore against small Android test doubles.

These are JVM integration checks, NOT device/instrumentation tests. Doubles model
successful SharedPreferences storage and explicit lifecycle/event/clock calls;
they do not simulate Android UI, process scheduling, disk failures or OEM behavior.
No packages are installed and no device settings or production data are accessed.
"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    java_home = os.environ.get('JAVA_HOME')
    if not java_home and Path('/usr/libexec/java_home').exists():
        java_home = subprocess.check_output(['/usr/libexec/java_home'], text=True).strip()
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    if not javac or not java:
        raise SystemExit('An installed JDK is required; nothing is installed automatically.')
    scratch = Path(os.environ.get('TVGUARD_TEST_TMPDIR',
                                  str(Path.home() / '.hermes/cache/scratch')))
    if not scratch.is_dir():
        scratch = Path(tempfile.gettempdir())
    with tempfile.TemporaryDirectory(prefix='tvguard-integration-', dir=scratch) as tmp:
        work = Path(tmp)
        source = work / 'src'
        stubs = json.loads((ROOT / 'tests/android-stubs.json').read_text(encoding='utf-8'))
        for name, text in stubs.items():
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
        package = 'com/hermes/tvguard'
        for name in ['GuardService', 'GuardState', 'Prefs', 'PinStore', 'PinRetryPolicy',
                     'Questions', 'VocabBank']:
            shutil.copyfile(ROOT / 'app/src/main/java' / package / (name + '.java'),
                            source / package / (name + '.java'))
        shutil.copyfile(ROOT / 'tests/integration' / package / 'AndroidIntegrationTest.java',
                        source / package / 'AndroidIntegrationTest.java')
        classes = work / 'classes'
        classes.mkdir()
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', str(classes)]
                       + [str(p) for p in source.rglob('*.java')], check=True)
        subprocess.run([java, '-ea', '-cp', str(classes),
                        'com.hermes.tvguard.AndroidIntegrationTest'], check=True)


if __name__ == '__main__':
    main()
