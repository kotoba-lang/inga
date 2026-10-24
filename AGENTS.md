# Agent rules

The shared `com-junkawasaki/root/AGENTS.md` contract applies. New canonical
source and final execution use Kotoba and Amu selfhost native. JVM, NBB,
Babashka, KBB and Node are not implementation or release-gate fallbacks.

The native prefix reader is a bounded static-validator profile. Its complete
hash/signature/ancestry/checkpoint closure must execute in native gates; a
caller-provided verification boolean or a count of witness names is not an
acceptable substitute. Keep validator membership and durable checkpoints
outside untrusted proof control.

The source-unity evidence in `docs/native-prefix.md` does not migrate the
whole existing Inga API or qualify canonical project linkage, native live
replicas, durable vote locks, crash recovery or production finality. Do not
promote prefix-reader tests into those claims. There is no qualified native
CI/controller in this repository; do not add a hosted fallback.
