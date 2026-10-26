# 0001 — Rename the application ID to io.github.amatsukiichika.iwaram

Status: accepted

## Problem

Version 0.1.0 shipped as `io.github.amatsukiichika.iwaramanager` while the product is named IwaraM; the application ID and the Kotlin namespace both carried the longer name, and every source path and the exported Room schema directory repeated it.
Android treats the application ID as the app's identity, not as a label: a build with a different ID cannot update an installed app, and the private data directory holding `iwara_manager.db` and the `prefs` SharedPreferences belongs to the old ID and is unreachable from the new one.
The README promises that every release is signed with the same certificate so a new version installs over an old one, and that promise does not survive an ID change; the certificate is unchanged, the package identity is not.
The cost stated in the changelog is therefore explicit: uninstall 0.1.0, install 0.2.0, and give up the library index, watch history and settings; media already downloaded into user-chosen folders is unaffected because those files live outside the app's private storage.

## Decision

The application ID and the Kotlin package are `io.github.amatsukiichika.iwaram`: `applicationId`, `namespace`, the source tree under `app/src/{main,test}/java/io/github/amatsukiichika/iwaram`, the exported schema directory and the proguard keep rule all moved together.
The change ships as a `**Breaking:**` changelog entry that names the uninstall requirement and the data loss, rather than leaving users to discover them as a failed install.
The new build never reads or migrates the old private data; it cannot, because that directory belongs to another package.
The rename is one-way: no later release can restore update eligibility for an existing 0.1.0 install.

## Alternatives considered

**Keep `iwaramanager` and live with the old name** — the only option that preserves the in-place update the README promises. The repository records the cost of renaming but no written reason for preferring it; what it records is timing, and the timing is the argument: 0.1.0 was released on 2026-10-24, 0.2.0-rc.1 was cut at 22:18, the rename merged at 23:14 the same day, and it first shipped in 0.2.0-rc.2. The break was paid while the installed base was hours old rather than accumulating.

**Rename the app and ship an exporter or a bridge build that carries the old data across** — what a user who reads "your history is gone" will ask for. The changelog's own accounting makes the case against it: the media files survive because the user chose their folder, the index can be rebuilt by re-importing that folder, and settings and history are re-creatable. Dual-package tooling for an install base less than a day old would have cost more than the loss.

## Consequences

Anyone on 0.1.0 or 0.2.0-rc.1 cannot update in place; the support answer is uninstall, reinstall, re-grant the media folder, sign in again, and the library is rebuilt by the normal scan.
The same-certificate promise in the README now holds from 0.2.0-rc.2 onward only, and any future generation that changes the ID repeats this record.
The exported Room schema path is part of the package and moved with it, so the schema JSON and the migration tests now live under `io.github.amatsukiichika.iwaram.data.AppDatabase`; the old schema directory is gone, which is harmless because no old install can ever migrate into the new package.
This is a one-way door: the record exists so a later reader knows the lost install base was a decision, not an accident.

