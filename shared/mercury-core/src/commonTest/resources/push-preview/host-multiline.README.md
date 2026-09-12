# Host-produced multiline preview regression fixture

`host-multiline.json` is the unmodified synthetic corpus produced by the installed
Mercury Relay host plugin's `build_preview_plaintext` and `encrypt_preview`, not
by a client reimplementation. Producer source SHA-256:
`152256b8b1cf669f1ad8a98be0fc2baeb112faea5b13ddbe0d21f3afc4264169`.
Fixture SHA-256: `ed85fd2d9c2dca7978ec82bc585e6f930cab425b695757e6a1671ca83edf3814`.

All inputs and keys are synthetic public test material. Environment is `sandbox`,
issued time is `2000000000`, TTL is 120 seconds, opener time is `2000000010`.
The producer was called with completion kind, title `Synthetic preview`, both
preview fields enabled, and route `synthetic-session` / `default`. Keys/wake/event/
key ID are the sequential byte values encoded in each row; nonce is the row
index as 12 big-endian bytes. Never use these deterministic values for real pushes.

Inputs cover a single line, LF, CRLF, and three lines. The host normalizes CRLF to
LF; the client must accept that authenticated LF body without accepting a raw
CR, or LF in title/session/profile. Swift tests bundle the fixture and open its
actual ciphertext through `PushPreviewProcessor.decrypt` without an optional
external fixture or skip. Shared host tests consume the same plaintext fields.
The original native RED accepted single-line and rejected the other three with
`malformedPlaintext`; its independent field/control negatives remained rejected.
