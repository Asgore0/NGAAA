# Confirmed user-write process queue

`run.sh` tests the production queue with local in-memory counters only. It checks
one active worker, two accepted waiting tasks, explicit rejection before work
when full, FIFO completion, no automatic retry, and capacity returning for a fresh
explicit confirmation. It uses no Android runtime, credentials, disk state or
network. Supply an absolute isolated `TEST_WORK_DIR`.
