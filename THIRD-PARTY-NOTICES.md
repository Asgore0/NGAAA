# Third-party source

The native QR encoder uses Nayuki's QR Code generator (Java), commit
`3c6d0b3cefb4e049dc337e82237c9644399716a8`, under the MIT License.

The complete copyright notice, permission grant and disclaimer are preserved at
the top of every vendored file in `app/src/main/java/io/nayuki/qrcodegen/`.
Upstream URLs and individual SHA-256 hashes are recorded in
[`third-party/nayuki-source.json`](third-party/nayuki-source.json).

Upstream: <https://github.com/nayuki/QR-Code-generator>.

NGA server response formats and public scripts were inspected for interoperability.
Open-source third-party clients were used as protocol references; their GPL
implementation code is not vendored or included in this application.

The test-only org.json dependency is downloaded separately with a pinned checksum.
It is not included as a runtime library in the APK or source archive.

## NGA public service metadata and images

`app/src/main/assets/forums.json` is public forum catalog metadata. The bundled
forum and collection icons in `app/src/main/assets/forum-icons/` are third-party
images, not original MIT-licensed project artwork. Their public HTTPS origins,
file hashes and capture metadata are recorded in
[`forum-icons/index.json`](app/src/main/assets/forum-icons/index.json).
Public accessibility and source hashes establish provenance; they do not grant
a new copyright license or imply NGA's endorsement of this client.

The generic default avatar is adapted from the official public renderer; its
origin and preserved geometry are described in
[`docs/DEFAULT-AVATAR.md`](docs/DEFAULT-AVATAR.md). NGA names, service content,
forum images and this avatar remain subject to their respective owners' rights.
The project's MIT license does not relicense them. No official APK, official
application implementation, personal user avatar cache or account data is
included in this repository.
