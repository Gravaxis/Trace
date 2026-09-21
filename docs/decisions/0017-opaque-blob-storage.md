# ADR-0017: Opaque blobs have storage identities, not producer-slot identities

* Status: implemented storage API; capture integration remains M4
* Date: 2026-09-21
* Milestone: M3

`EventStore.putBlob` stores opaque bytes and a format version under a SHA-256
identity that includes that version. Reads recompute the identity; a missing or
damaged payload is an error. `attachBlob` requires an existing event and payload,
refuses a conflicting attachment, and keys the reference by world and the absolute
`CursorPosition`. `blobAt` supplies a stable read path for scan consumers regardless
of shard relocation. Explicit collection removes unreferenced payloads only while
no scans are open. Purge removes matching references transactionally with history.

This is a deliberate revision to the execution plan: opaque payload writes and
explicit attachments use SQLite FULL commits rather than inventing a transient
sidecar pairing or a journal protocol that capture cannot yet produce. Payload
storage and attachment are separate operations. A crash between them leaves an
unreferenced payload, not a dangling reference. An event without an attachment is
not claimed to be a complete captured block entity. M4 must define journalled,
atomic event-plus-payload capture before using this for real block entities.

`BlobStorageTest` covers exact bytes, empty payloads, version-separated identities,
reopen, missing events/payloads, corruption, compaction and partial/final purge.
`StorageCrashTest` kills a child JVM after payload persistence and after reference
persistence, then reopens twice and checks exact bytes and attachment presence.
These tests do not prove NBT interpretation, DataFixer behavior, container fidelity,
zero-allocation capture, compression, or power-loss durability. Blob costs are
**not measured**. The payload limit is a defensive bound, not a measured optimum.
