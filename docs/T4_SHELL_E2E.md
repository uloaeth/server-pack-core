# T4 — e2e checklist: `shell whoami` against a deployed agent

Execute against a real, enrolled agent before T7. Every step lists the expected
result; stop at the first mismatch. The task is `http_only`: the real body must
travel by signed HTTP and the frame only carries the marker.

## Preconditions

1. The victim is online and the panel log shows its `hello` completed.
2. `GET /api/v1/tasks` from the agent sees no other queued work.
3. The operator opens the victim and sends `shell` with `command: whoami`,
   `timeout_sec: 60`.
4. Expected: `201` from `POST /api/console/tasks` with one task id; the panel
   sends a `cmd` frame and the agent ACKs it (transport ACK) within 5 s.

## Short output

5. Expected: the agent runs the command and POSTs
   `/api/v1/tasks/<task_id>/result` signed with VictimHmac. Response `200`
   `{ok:true}`. Body: `status:"ok"`, `error:null`, `result.exit:0`,
   `result.output` equals the account name, `artifacts:[]`.
6. Expected: one `result` frame follows with
   `body {status:"ok",error:null,result:{"http_result":true},artifacts:[]}` and
   is ACKed. No command output is in the frame.
7. Expected: the task is `done`, `result.output` shows the name, and no
   artifact row exists (output is under the 1900-character upload threshold).

## Long output (chunk and commit)

8. Send `shell` with a command that prints >1900 characters (for example
   `seq 1 500`).
9. Expected: signed `POST /api/v1/artifacts` returns `201` with `upload_id`,
   `total_size`, `chunk_count` and `sha256`; the result body names
   `upload_id` in `result`.
10. Expected: `POST /api/v1/artifacts/<upload_id>/chunk` for offsets
    `0, CHUNK, ...` in order, each `200`/`201`; a repeated offset returns the
    existing chunk, not a second copy.
11. Expected: `POST /api/v1/artifacts/<upload_id>/commit` returns `200`; the
    artifact moves to `queued` then `processing`.
12. Expected: the task is `done` with `result.upload_id`; the artifact appears
    in Files under the task id; `sha256` of the stored file matches the init
    value and the byte count equals `total_size`.
13. Expected: the marker frame is present and ACKed; no second HTTP result is
    stored (late duplicate returns `409 already_finished`, task stays `done`).

## Failure checks

14. Stop the agent before step 4: the frame `cmd` stays ACKed until pong
    timeout and the task ends `timeout` after `timeout_ms + 5 s`, never
    `done`.
15. Send six shells at once: the first four run, the fifth and sixth are ACKed
    and end `error:"busy"`. No task sits `queued`.

## Cleanup

16. Delete the artifact if the retention job has not run; revoke nothing else.
