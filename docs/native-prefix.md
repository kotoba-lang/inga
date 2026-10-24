# Native static-validator finalized-prefix reader

`kotoba/inga/native_prefix.kotoba` verifies a complete bounded prefix from a
trusted empty genesis, every parent link/height, monotonically increasing
rounds, ordered validator leader election, parent QCs and a certified tip.
It recomputes SHA256 over the existing `inga.consensus/canonical-block`
serialization and verifies actual Ed25519 signatures over the existing
`inga.attest/vote-payload` string. Counting keys or caller-supplied booleans
is not a verification path.

This is a **restricted wire adapter**, not a new vote/block signing format.
Witness IDs must equal their admitted 32-byte public key in lowercase hex;
the context's CSV order defines leader rotation. Existing arbitrary witness
aliases, stake-weighted membership, dynamic validator sets and transport
parsers have not been migrated. Wire v1 encodes blocks as
`height|parent|proposal-CIDs-CSV|proposer|timestamp|round|parent-QC`, one per
line with no trailing newline. QC is `height:hash:vote;vote...`, where each
vote is `witness,view,signature`. Numbers are canonical decimal bounded below
2^53. The only unsigned QC exception is the exact trusted genesis parent of
height one. All non-genesis parent QCs and the tip QC require signatures.

`verify-prefix` returns the finalized height under the existing three-chain
rule (four verified blocks finalize height one), or a negative refusal code.
It rejects the entire proof if any suffix is bad. `verify-extension` also
requires a separately trusted `height:hash` checkpoint and refuses rollback
or a conflicting certified branch with code -8. The proof peer must never
choose that checkpoint. Other refusal codes are documented in source.

Limits: 64 blocks, 16 admitted keys, 64 proposals/block, 1 MiB prefix, 4096
bytes tip QC. The full resource envelope is unqualified. Bounded prefix
verification has no network or storage side effects and cannot establish
validator independence, durable vote locks, live replica safety, timeout
recovery or durable checkpoint publication. In particular, valid signatures
on two branches do not prove one branch impossible. The fork fixture
intentionally signs both branches with public test keys and verifies that a
trusted checkpoint prevents switching.

The [receipt](evidence/awai81-native-20261024/receipt.json) records an arm64
Linux **source-unity** native gate. Crypto bodies are pinned from upstream
main; namespace renaming retains every provider function, and test `main` is
the static export. No JVM, Node, host crypto, C compiler or C loader runs in
the tested executable. OpenSSL and Python hashlib generate independent
public test fixtures; no production keys or services are used. This is not
canonical project linkage, whole Inga migration, artifact admission, or a
native node/replica deployment. Existing runtime selection is unchanged.
