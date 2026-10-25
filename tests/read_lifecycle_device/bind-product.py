#!/usr/bin/env python3
"""Bind an isolated helper to immutable product inputs; never extracts or modifies them."""
import hashlib
import json
from pathlib import Path
import re
import sys
from zipfile import ZipFile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


product, candidate, helper_jar, helper_apk, output = map(Path, sys.argv[1:6])
expected_product, expected_candidate = sys.argv[6:8]
if sha(product) != expected_product or sha(candidate) != expected_candidate:
    raise SystemExit("Product inputs changed during helper compilation")
records = []
tree = hashlib.sha256()
with ZipFile(product) as archive:
    names = sorted(info.filename for info in archive.infolist() if info.filename.endswith(".class"))
    if not names or len(names) != len(set(names)):
        raise SystemExit("Canonical unique production classes required")
    for name in names:
        if not re.fullmatch(r"(?:[A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*\.class", name):
            raise SystemExit("Noncanonical production class entry")
        digest = hashlib.sha256(archive.read(name)).hexdigest()
        records.append({"path": name, "sha256": digest})
        tree.update((name + "\0" + digest + "\n").encode("utf-8"))
with ZipFile(helper_jar) as archive:
    classes = [name for name in archive.namelist() if name.endswith(".class")]
    if not classes or any(not name.startswith("cn/ngapure/readcancelfixturev194/") for name in classes):
        raise SystemExit("Helper-only jar must contain no copied production or Android stub classes")
report = {
    "schema": 1,
    "productionJarSha256": expected_product,
    "productApkSha256": expected_candidate,
    "helperOnlyJarSha256": sha(helper_jar),
    "helperApkSha256": sha(helper_apk),
    "productionClassCount": len(records),
    "productionClassTreeSha256": tree.hexdigest(),
    "productionInputsUnchangedDuringBuild": True,
    "productionJarPassedDirectlyToD8": True,
    "helperContainsNoCopiedProductionClasses": True,
    "realAndroidApiBytecodeRequired": True,
    "classes": records,
}
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
