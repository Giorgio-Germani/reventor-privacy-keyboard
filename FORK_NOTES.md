# REVENTOR Privacy Keyboard — Fork Notes

This repository is a standalone fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard).
This document records what was changed to make it its own product, what was
intentionally kept, and what remains if full ownership is ever desired.

## Voice input

Voice recognition runs entirely on-device with NVIDIA Canary 180M Flash
(see [CANARY_SETUP.md](CANARY_SETUP.md)). The spoken language is **not**
auto-detected:

- The spacebar permanently shows the current dictation language.
- Switching the keyboard language (long-press/swipe on the spacebar) also
  switches the dictation language when the language is supported
  (English, German, Spanish, French). Unsupported keyboard languages leave
  the dictation language unchanged.
- The language can also be tapped directly in the voice input window (chips
  at the top). The microphone button never changes the language.

## Clipboard sync (added by this fork)

`Clipboard Sync` copies text between this keyboard and paired Windows/macOS/
Linux desktops running the `:desktop` tray app (`desktop/` module), plus the
shared `:sync-protocol` module. Design decisions worth remembering:

- **Offline-first stance change**: this feature re-adds the `INTERNET` and
  `ACCESS_NETWORK_STATE` permissions (the latter was explicitly stripped via
  `tools:node="remove"` before). Traffic is direct LAN peer-to-peer, E2E
  encrypted (ECDH P-256 + AES-256-GCM, SAS-verified pairing); nothing is sent
  to any server. Text clips only, sensitive clips skipped, 500k char cap.
- The engine runs in the IME process (`uix/clipboardsync/`), which is what
  makes background clipboard access work on Android 10+. The phone is
  connect-only; desktops listen on TCP 42240 and advertise via mDNS
  (`_reventorsync._tcp`).
- Clips captured while a peer was unreachable are reconciled on the next
  connection (each side announces its current clip when a link goes live).
  PC→phone delivery therefore happens when the keyboard next appears; an
  optional same-process foreground service could make it instant (future work).
- **Desktop installers** are built per-OS (jpackage cannot cross-build): run
  `gradlew :desktop:packageDistributionForCurrentOS` on Windows (→ MSI),
  macOS (→ DMG), or Linux (→ AppImage + deb) under
  `desktop/build/compose/binaries/main/`. Unsigned builds trigger
  SmartScreen/Gatekeeper warnings ("run anyway" / right-click-open).

## Removed from upstream FUTO Keyboard

| Feature | Reason |
|---|---|
| Whisper voice recognition (engine, models, native code) | Replaced by Canary; the bundled whisper-tiny LID model crashed the IME process (see commit history) |
| Transformer / gguf language-model feature (`xlm/` package, model manager UI, finetuning, llama.cpp + sentencepiece in the native lib) | No models are bundled; predictions use the dictionary engine, which is what happened in practice anyway |
| Updater (`org.futo.inputmethod.updates`, update settings items, background check service) | A fork must not offer to "update" users into the official FUTO Keyboard; the update server is FUTO's |
| FUTO payment flow (Pay menu, payment nag, license deep links, `PaymentCompleteActivity`) | Not applicable to this fork |
| ACRA crash reporting | Disabled upstream by default and pointed at `keyboard@futo.org`; crashes are now debugged via logcat |
| FUTO CI scripts (`uploadNightly*`, `sendZulipMessage.sh`, `setUpPropertiesCI.sh`) | FUTO infrastructure only |
| "Languages & Models" hidden menu | Replaced by a visible **Languages** settings screen managing layouts + dictionaries only |

Help and Credits screens point to the fork's GitHub
(`Giorgio-Germani/reventor-privacy-keyboard`), with upstream attribution kept.

## Intentionally kept

- **applicationId `org.futo.inputmethod.latin`** — renaming it would break
  every hardcoded reference (mic permission activity, voice input service
  binding) and reinstall/upgrade flows. Renaming is a larger project; the
  user-visible name everywhere is "REVENTOR Privacy Keyboard".
- **Dictionary addon catalog** (`keyboard.futo.tech/dictionaries`) — the only
  hosted source of compatible `.dict` dictionaries; generic infrastructure.
- **LayoutSpec developer docs link** in the layout editor — upstream
  documentation of the layout format, still accurate.
- **NOTICE** (AOSP Apache-2.0 attribution) — required.
- Credits sections for AOSP, mozc, sherpa-onnx, WebRTC VAD, etc. — required
  third-party attribution, plus "based on FUTO Keyboard".

## Remaining checklist for full ownership

1. **License**: `LICENSE.md` is FUTO's *Source First License 1.1*. Decide
   whether redistribution under the REVENTOR name is compatible with it, or
   negotiate/replace (the AOSP parts are Apache-2.0).
2. **README.md** still describes FUTO Keyboard (clone URLs, contribution/CLA
   flow) — rewrite for the fork.
3. **Translations** (`translations/` submodule) and `java/res-large` are
   FUTO's; unused payment/update string keys remain in `strings-uix.xml`.
4. **Flavors**: `unstable`, `stable`, `playstore` all still build; the
   playstore flavor's payment-app code paths are gone but its
   manifest/`IS_PLAYSTORE_BUILD` remain. Consider collapsing to one flavor.
5. **Branding sweep**: a few internal theming names (e.g. "FUTO VI Theme"),
   `voiceinput-shared` package `org.futo.voiceinput.*`, and the `futo_o.xml`
   drawable still carry FUTO names — invisible to users, rename if desired.
6. **Signing**: releases use `keystore.properties` (local, not in git).
