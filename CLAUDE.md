# Rule: Mandatory Graphify Usage

- For any codebase analysis, dependency tracing, or architecture inquiry, your FIRST tool call must be querying the local knowledge graph.
- Run `graphify query "<question>"` or use the graphify skill (`graphify-out/graph.json`) instead of raw `grep`, `find`, or cascading file reads.
- Only fall back to direct file reading if the specific snippet is missing from the graph or needs exact line verification.
