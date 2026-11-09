# Contract v2

Copy of v1 with additive fields and types only. Every v1 endpoint and semantic is
unchanged and stays valid; a 0.3.7 agent continues to operate v1 against a 0.4
server.

Negotiation:

- `/enroll` accepts `device_token`, `key_epoch` and `capabilities`; it returns
  `contract: 2`, `identity_state`, `key_epoch` and the persisted intersection.
- `GET /capabilities` is authenticated and refreshes the intersection.
- `X-Contract: 2` is reinforcement; a missing header is v1.
- A capability the server cannot serve yet answers `501
  capability_not_implemented` with `victim_ids`.
- Frames and task schemas for each capability arrive with its feature; the
  capability is advertised only then.
