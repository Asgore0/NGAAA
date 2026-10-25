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
            raise SystemExit('Duplicate or noncanonical production class filename forbidden')
        payload = archive.read(name)
        digest = hashlib.sha256(payload).hexdigest()
        aggregate.update(name.encode('utf-8'))
        aggregate.update(b'\0')
        aggregate.update(digest.encode('ascii'))
        aggregate.update(b'\n')
        records.append({'path':name,'sha256':digest})
with ZipFile(helper_only) as helper:
    if any(name.startswith('cn/ngapure/app/') for name in helper.namelist()):
        raise SystemExit('Stale copied production classes forbidden in helper-only jar')
report = {
    'schema':2,
    'productionJar':str(product.resolve()),
    'productionJarSha256':hashlib.sha256(product.read_bytes()).hexdigest(),
    'realAndroidApiBytecodeRequired':True,
    'htmlFromHtmlDescriptor':'(Ljava/lang/String;I)Landroid/text/Spanned;',
    'jvmHtmlStubForbidden':True,
    'staleCopiedClassesForbidden':True,
    'd8Inputs':['final-production-classes.jar','helper-only.jar'],
    'productionClassesSuppliedDirectlyFromFinalBuildJar':True,
    'productionClassCount':len(records),
    'productionClassTreeSha256':aggregate.hexdigest(),
    'productApkSha256':hashlib.sha256(candidate.read_bytes()).hexdigest(),
    'helperApkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'classes':records,
}
output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
