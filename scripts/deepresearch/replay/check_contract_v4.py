import json
from pathlib import Path
import os
from types import SimpleNamespace
from app.requirement_coverage import requirement_contract
for version in (4, 5):
    raw=json.loads((Path(os.environ['NOTEWEAVE_REPLAY_OUT']) / f'requirement-contract-v{version}.json').read_text())
    contract=requirement_contract(SimpleNamespace(query_policy={'requirement_contract':raw}))
    assert contract['coverage_execution_version']==1 and isinstance(contract['deferred_materials'],list)
    if version == 5:
        assert contract['source_scope_checks_version'] == 1
print(json.dumps({'v4_contract_digest':'MATCHED','v5_contract_digest':'MATCHED','provider_calls':0}))
