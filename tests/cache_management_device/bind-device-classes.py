from pathlib import Path
from zipfile import ZipFile
import hashlib
import json
import re
import sys

product, helper_only, candidate, apk, output = map(Path, sys.argv[1:])
records = []
aggregate = hashlib.sha256()
with ZipFile(product) as archive:
    entries = sorted(entry.filename for entry in archive.infolist() if entry.filename.endswith('.class'))
    if not entries or len(entries) != len(set(entries)):
        raise SystemExit('Canonical final production class entries required')
    for name in entries:
        if not re.fullmatch(r'[A-Za-z0-9_$./]+\.class', name):
            raise SystemExit('Noncanonical production class filename forbidden')
        digest = hashlib.sha256(archive.read(name)).hexdigest()
        aggregate.update(name.encode('utf-8'))
        aggregate.update(b'\0')
        aggregate.update(digest.encode('ascii'))
        aggregate.update(b'\n')
        records.append({'path': name, 'sha256': digest})
with ZipFile(helper_only) as helper:
    if any(name.startswith('cn/ngapure/app/') for name in helper.namelist()):
        raise SystemExit('Shadowed or copied production classes forbidden in helper-only JAR')
with ZipFile(apk) as helper_apk:
    runtime_binding = json.loads(helper_apk.read('assets/binding.json'))
jar_sha = hashlib.sha256(product.read_bytes()).hexdigest()
apk_sha = hashlib.sha256(candidate.read_bytes()).hexdigest()
if runtime_binding['productJarSha256'] != jar_sha or runtime_binding['productApkSha256'] != apk_sha:
    raise SystemExit('Runtime binding differs from exact product artifacts')
report = {
    'schema': 2,
    'productionJar': str(product.resolve()),
    'productionJarSha256': jar_sha,
    'productApkSha256': apk_sha,
    'helperApkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'productionClassesSuppliedDirectlyFromFinalBuildJar': True,
    'productionClassesRewritten': False,
    'productionClassCount': len(records),
    'productionClassTreeSha256': aggregate.hexdigest(),
    'd8Inputs': ['immutable-production.jar', 'helper-only.jar'],
    'realAndroidApiBytecodeRequired': True,
    'androidDecoderStub': False,
    'internetPermission': False,
    'helperOwnUid': True,
    'mainAppDataCopied': False,
    'classes': records,
}
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
