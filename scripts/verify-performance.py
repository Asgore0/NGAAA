#!/usr/bin/env python3
"""Recompute every published candidate, including failed experiments, without a device."""
from pathlib import Path
import hashlib,importlib.util,json
folder=Path(__file__).resolve().parents[1]/'docs/performance'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
protocol=json.loads((folder/'protocol.json').read_text())
assert sha(folder/'protocol.json')=='c790a2ab5ec94de055d7ca697429393b8bd0f44e8c11be02fefa801e60ce48df','Protocol changed'
spec=importlib.util.spec_from_file_location('analysis',folder/'analysis.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
paths=sorted(folder.glob('candidate-*.json'))
assert paths,'No comparison data'
manifest=json.loads((folder/'manifest.json').read_text())
assert manifest['schema']==1
assert {p.name for p in paths}==set(manifest['comparisons']),'Comparison missing or unregistered'
for path in paths:
    data=json.loads(path.read_text())
    assert path.name==f"candidate-{data['candidate']}.json",'Candidate filename differs'
    assert sha(path)==manifest['comparisons'][path.name],'Registered comparison changed'
    assert data['protocolSha256']==sha(folder/'protocol.json') and data['analysisSha256']==sha(folder/'analysis.py')
    result=module.analyze(data['samples'],protocol)
    assert result==data['result'],f'Calculation mismatch: {path.name}'
    assert data['preconditioningRunsExcludedFromLatency']==42 and data['preconditioningAllPassed']
    assert data['credentialsOrAccountIdentifiersIncluded'] is False
    print(f"c{data['candidate']}: calculations verified; stable speedup={'PASS' if result['passed'] else 'NOT DEMONSTRATED'}")
print(f'Verified {len(paths)} complete comparisons. This is evidence validation, not a release gate.')
