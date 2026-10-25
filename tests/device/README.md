# Live device protocol verification

Build the app first, then `bash tests/device/build.sh`. The helper must use the
same signing key as that app. Install it only on a device/account you intend to
test. Read-only checks run through `cn.ngapure.verify/cn.ngapure.verify.NativeVerify`.

The default checks read public feeds, search, the current user's profile, inbox
list, reminders, and server favorites. They perform read-only new/reply prepare
requests (including server quote text) and a local-only draft save/read/delete.
They also read one conversation already marked read, if available. They never
publish, upload, send messages, or modify server favorites. Diagnostic results contain counts and field types;
they do not contain account identifiers, credentials, nicknames or message text.

Passing instrumentation argument `-e checkIn true` additionally performs a real
daily check-in, which changes server account state if today's check-in has not
already completed. This is opt-in. Check-in success is recorded only from explicit
server confirmation.

The installed helper is a separate test package and is not included in the
production APK. The release's device record states the exact APK tested and the
checks that passed or failed; synthetic fixture results do not imply live success.

The 0.19.4 local fixtures follow the fixed-material glass implementation. They
cancel pending navigation and page reads before swapping fixture content; there
is no backdrop refresh task to cancel or page screenshot sampler to retain.

`ProtocolProbe` reports only sanitized protocol shapes. Optional argument
`-e sessionExchange true` also exercises the exact native exchange/verification
component with the device's retained account session. Credentials stay in device
memory, the result is not saved to account storage, and this is explicitly **not**
proof that a newly scanned QR grant was received and stored successfully.

`MediaProbe` uses only synthetic local posts, generated drawables and worker
barriers. It checks the 24-request media bound, queued cancellation, late/cache
callback suppression, memory trimming without recycling displayed bitmaps,
viewport loading, per-image animation visibility and reading-position stability
when an earlier image changes height. Its test image transport is blocked or
closed before views attach; no synthetic image URL is requested. It temporarily
replaces and restores the activity's content view. Launching the activity may
perform its normal public reads, so the probe refuses to launch when automatic
sign-in would be eligible to write account state. Results contain fixed boolean
keys and counts only, with no screenshots, content, media URLs or account data.

`TypographyProbe` temporarily replaces and restores the content view with a
synthetic local post. It checks nested quote content, the original code whitespace
and horizontal code scrolling, actual native center alignment, underlined links,
lazy expand/collapse, and measured layout widths of 280/620 dp. A 32-level quote at
28 sp must retain at least 180 dp of reading width on the narrow layout. Its media
loader is closed before the synthetic tree is built; no test image or link is
requested. It uses the same automatic sign-in eligibility guard as `MediaProbe`.
The fixed boolean results are returned under `typography_probe`; no pixels, user
content or identifiers are reported. These synthetic widths supplement the
release's real emulator window tests.
