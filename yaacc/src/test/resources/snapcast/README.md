# Snapcast test fixtures (issue #319)

Provenance: **derived**, not captured. The shapes come from the Snapcast documentation (`doc/json_rpc_api/control.md`) and the server sources (v0.35.0, `server/control_requests.cpp`); all values are synthetic (hosts `TEST-HOST-x`, MACs `02:00:00:00:00:xx`, IPs from `192.0.2.0/24`, stream names `Default` and `YAACC test phone`).

Known divergences to confirm on the rig and then replace with real captures (`tools/snapcast-rig/`):
- `response_add_stream.json` uses the shape of the source (`{"id": ...}`); `response_add_stream_doc_shape.json` uses the shape in the documentation (`{"stream_id": ...}`). The parser accepts both.
- The error documents (`error_*.json`) follow the server sources: a fixed `message` title and the reason in `data` (-32601, -32602, -32603), and 401 "Unauthorized" / 403 "Forbidden" without data. A duplicate stream name or a taken port gives -32603 with the exception text in `data` (settled from the source; the exact text is confirmed on the rig).
- `batch_notifications.ndjson` is one JSON array on a line: the server may answer and notify in batches (documentation, "Batch"); the RPC reader must accept arrays.
- The server may send `mac` and `ip` empty in `server.host`; `status_*.json` do so.

A grep for real MAC/IP patterns must find nothing here (only the synthetic ranges above).
