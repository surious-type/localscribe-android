# Task D integration residual fix

## Change

The download coroutine now publishes `CANCELLED` only while its own lazy-job
reservation still owns the descriptor under `jobsMutex`. It removes that same
reservation with the publication. A cancelled former owner therefore cannot
overwrite a replacement owner that has already published `COMPLETED`.

## Regression

The model concurrency suite gates the first coroutine after activation but
before it claims ownership. It then cancels and replaces that reservation,
allows the replacement to complete, and resumes the former coroutine. The
assertion verifies the visible terminal state remains `COMPLETED` and exactly
one replacement transfer ran.

## Verification

No Gradle, formatting, compile, or test command was run for this residual fix:
the UI integration worker owns that window. The implementation awaits the
coordinated checks before any passing claim is made.
