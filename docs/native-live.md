# Bounded native voting and durable signer

AWAI-81 adds an experimental static-membership voting path alongside the
historical finalized-prefix reader. Ordinary checked Kotoba modules provide
proposal/vote/QC verification, replica state transitions, signed new-view
messages, timeout certificates and durable signing. Native TCP processes
exercise this path locally. Existing runtime selection and public Inga APIs
are unchanged.

`native-live-context` binds the base chain, genesis, ordered public keys and
quorum into a SHA256 signing-domain suffix. Witness IDs are admitted public
keys, and the signing seed must derive the configured witness. Membership,
quorum and the durable checkpoint are trusted configuration. A peer cannot
supply a verification flag or a replacement quorum.

`native-replica` prepares pure intents. A replica votes for at most one block
hash in a view; an exact retry reproduces the same deterministic signature.
It verifies the complete ancestry, elected proposer signature and parent QC,
and applies the locked-branch / higher-justification rule. Actual distinct
signed votes form a QC. Certified parents update the lock and the existing
three-chain rule advances the committed checkpoint. New-view signatures bind
the high QC identity; only a distinct-witness timeout quorum advances a
stalled view. Duplicate votes or timeouts do not create a quorum.

The live adapter requires every vote in a QC to name its block's round. The
historical `verify-prefix` reader continues to accept its recorded mixed-view
fixtures. The native proposal header is `engi/native-proposal/v1`; vote and
new-view payloads retain their named `engi/*/v1` field encodings. These
restricted ASCII adapters do not migrate the complete historical transport,
validator alias, stake or membership interfaces.

`native-vote-store` takes a native kernel flock and validates the persisted
context, checksum, ancestry, high QC, lock, commit and vote intent. It writes
the next envelope, fsyncs the file, renames it over the state file and fsyncs
the directory **before computing or releasing a signature**. View, vote,
lock and committed watermarks cannot decrease through this API. A corrupt
state cannot silently reset the signer. The lock file keeps its inode;
unlock/durability uncertainty withholds a clean success.

## Recorded native checks

[The receipt](evidence/awai81-native-live-20261025/receipt.json) pins sources,
providers, KSL1 closures, compiler and loaderless AArch64 Linux artifacts.
The compiler is the full31 snapshot measured in the dependent
[Amu scalar-depth candidate](https://github.com/kotoba-lang/amu/pull/1568),
with F1 = F2 = F3. The parent Amu branch's newer public product is a separate
integration/qualification boundary.

| Gate | Result |
| --- | ---: |
| Strict live proof/signature and historical-reader controls | 15/15 |
| Replica, duplicate/equivocation and signed view-change controls | 30/30 |
| Separate-process signer, five SIGKILL windows, lock race, corruption and wrong key/context | 51/51 |
| Native TCP replicas | 4 witnesses, quorum 3 |
| Independent OpenSSL check of actual native proposal/vote/new-view signatures | 24 |

The TCP test forms three successive native QCs, stops the scheduled next
leader, forms a native TC using the other three processes, and certifies the
next view. The restarted process catches up to the same view and committed
hash while retaining its actual older local vote watermark. A Byzantine
fixture signs a conflicting proposal with the same public test key in a
separate store. Prior voters refuse equivocation; the catching-up node
refuses the stale view. Repeating one witness's valid vote never forms a QC.
The host routes bytes and injects faults; it does not sign or form a QC/TC.

The crash driver stages the exact intent envelope through the same native
I/O operations and pauses at partial write, completed write, file fsync,
rename and directory fsync. SIGKILL/restart recovers a complete old/new state
as appropriate; post-rename intent retries preserve the vote fence. These
are process-crash and cooperating-writer checks, not physical power-loss or
protection against a raw filesystem writer rolling back a valid old image.

## Explicit profile boundaries

The measured network is host-loopback published native TCP. The native
transport admits one newline-terminated ASCII request per connection, at
most 4 MiB including framing, bounded read chunks and timeouts. Listener
addresses and the filesystem root are explicit runtime grants. No dial
capability is granted. Test `propose`, `timeout`, `state` and `stop` commands
are trusted harness controls; this entry point uses public RFC test keys and
is not a public authenticated node/control plane.

Limits include 64 blocks, at most 16 configured witnesses, 64 proposal
identifiers per block, bounded canonical decimal views, 4 MiB events/state
and an 8.5 MB envelope. The measured fault scenario is four witnesses with
one offline/Byzantine witness. Whole-envelope resource qualification,
arbitrary network schedules, production key custody, public transport/API,
dynamic membership, external rollback anchors and physical power loss remain
open. These selected gates are not a universal distributed safety proof or
production deployment.

Kotoba VM separately consumes a live-certified real Cell request CID with
its grant and Cell signature, executes the unchanged raw Ethereum transaction
and persists its independently checked Ethereum stateRoot. That consumer
keeps consensus ordering, Cell authority and Ethereum execution distinct.
