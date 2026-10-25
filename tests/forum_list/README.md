# Forum list anchors

`bash tests/forum_list/run.sh` runs 49 pure Java assertions. Set `TEST_WORK_DIR`
to retain an isolated result. Tests cover exact account/host/token content scope,
route/parent/page ownership, topic identity after reorder/filtering, removed-topic
fallback, header offsets, state schema bounds and malformed input. This suite does
not claim Android layout correctness or startup acceleration.
