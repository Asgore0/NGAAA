# Board directory pure logic checks

Run `bash tests/board_directory/run.sh`. The suite compiles only the production
`BoardDirectory` and `ForumRoute` with JDK 17 / Java 8 target. No Android framework,
device, external dependency, account, network or persisted data is used.

Rows accept canonical ordinary board keys or `stid:` collection keys and legacy
four-column inputs. Duplicate canonical identities keep their first display order
while merging search aliases and newline-separated category memberships. Invalid
keys, short rows and missing names are skipped; optional missing values are empty.

Search applies NFKC, `Locale.ROOT` case conversion and Unicode whitespace collapse
to both the query and searchable fields. All tokens must match, including tokens
spread across name, alias and category. Six ranking tiers are exact ID, exact name,
name prefix, all tokens in name, tokens covered by ID/name/aliases, then category
matches. Favorites only break ties for nonempty searches. Empty searches keep the
original first-seen order. `parentFid` is routing metadata rather than the child's
own searchable identity. The UI owns any special favorites-only mode.

Grid columns use actual measured content width after caller-side gutters. Packing
groups primary categories by first appearance, separates headers from tile rows,
and locates adapters by canonical board key after column or order changes. Returned
row/entry lists cannot be edited, and reading an entry returns a defensive array
copy so an external mutation cannot corrupt an existing discovery snapshot.
