# Document ID Mapping

Some external benchmark suites use their own document identifiers. NoteWeave evaluation needs internal `documentId`, `chunkId`, or `wikiPageId` values to score retrieval correctly.

## Why This Exists

EnterpriseRAG-Bench, CiteRAG, and some other suites may ship gold references using benchmark-specific ids such as:

- `dsid_*`
- paper ids
- citation ids
- passage ids

NoteWeave runtime and `rag_eval_case.expectedSourceJson` need internal ids when the source documents are actually imported into NoteWeave.

## Prepared Flow

1. Download the raw benchmark slice.
2. Import the source documents into NoteWeave later.
3. Export a local mapping file from the imported corpus:
   - `external_doc_id`
   - `document_id`
4. Use `scripts/build_doc_map.py` to convert the mapping into a JSON object.
5. Re-run the export helper so `expectedSourceJson` includes internal ids.

## Current Status

- No backend import has been performed yet.
- No doc-id map has been generated from live NoteWeave data yet.
- The scripts and file contracts are already prepared.
