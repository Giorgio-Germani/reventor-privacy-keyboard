## Goal
Clean the fork of everything that doesn't belong in "REVENTOR Privacy Keyboard", fix the mic/spacebar language behavior, and produce a fork-handover overview document.

## 1. Voice language switching — behavior fixes (your new bug report)
- **Mic button**: remove the long-press language cycling I added (`altPressImpl` in `VoiceInputAction.kt`). The mic becomes press-only again — a short tap can never change the language. Language switching happens exclusively via the spacebar.
- **Spacebar switches layout + voice language together**: in `LatinIME` where the keyboard subtype changes (`changeSubtype`/`onNewSubtype`), map the newly selected keyboard language to the voice language (en/de/es/fr) and update `VoiceLanguageState` + the `voice_language` setting, so the spacebar label and dictation language follow the selected language. If a keyboard language isn't supported by Canary (not one of the 4), the voice language stays as-is (label unchanged).

## 2. Remove the transformer-model feature (not bundled, unused)
- Settings UI: delete `pages/ModelManager.kt`, `pages/modelmanager/` package, `pages/AdvancedParameters.kt`, orphaned `pages/TrainDev.kt`; remove transformer items from `PredictiveText.kt` (toggle, "Transformer models" entry, advanced-params entry, alpha notice); remove `SettingsNavigator.kt` routes/imports.
- `Languages.kt`: remove remaining transformer row/strings/`transformerModel` plumbing (screen gets restored as "Languages", see §6).
- Engine: delete `xlm/` package (LanguageModel, LanguageModelFacilitator, ModelPaths, AdapterTrainer, Training*, BatchInputConverter); unwind callers in `GeneralIME.kt`, `Suggest.java`/`SuggestedWords.java` transformer flag, `InputLogic.java`, `Settings.java`/`SettingsValues.java` (`PREF_KEY_USE_TRANSFORMER_LM`, `mTransformerPredictionEnabled`, the `|| mTransformerPredictionEnabled` in bigram prediction), `SettingsValuesForSuggestion`, `ActionBar.kt` transformer suggestion icon/filter, `SettingsActivity.kt` gguf export, `SettingsExporter.kt` transformer backup blocks, `DevSettings.kt` toggle.
- Native: delete `org_futo_inputmethod_latin_xlm_{LanguageModel,AdapterTrainer,ModelInfoLoader}.cpp/h`, `src/ggml/` (llama.cpp, LanguageModel.cpp, ModelMeta.cpp, finetune, train, common — keep `unicode.h`, which `dictionary_itrie.cpp` includes, by moving it to `src/utils/`), `src/sentencepiece/` + its third_party deps (absl/esaxx/darts_clone/protobuf-lite entries) from `NativeFileList.cmake`; remove the three JNI registrations from `jni_common.cpp`.
- Predictions fall back to the legacy dictionary engine — same behavior as today (no model is bundled), just without dead code and UI.

## 3. Remove the updater (must not offer updating into FUTO Keyboard)
- Delete `org/futo/inputmethod/updates/` package (`UpdateChecking.kt`, `UpdateCheckingService.kt`, `Update.kt`, `InstallReceiver.kt`, `UpdateResult.kt`) and `uix/settings/UpdateScreen.kt`.
- Remove: `LatinIME.kt` startup job `scheduleUpdateCheckingJob`, `Home.kt` "Check for updates" item + `ConditionalMigrateUpdateNotice` banner, `SettingsNavigator` update routes, `UixManager.kt` update nudge, `java/stable/AndroidManifest.xml` service + receiver declarations, `UPDATE_CHECKING*`/`IS_PLAYSTORE_BUILD`-adjacent buildConfig fields in `build.gradle`, related strings.

## 4. Remove the FUTO payment flow
- Delete `pages/Payment.kt`, `latin/payment/PaymentCompleteActivity.kt` (+ manifest entry), `strings-payment-app.xml`; remove Home "Pay" item, `ConditionalUnpaidNoticeWithNav` nag, "Paid version" indicator, navigator routes (`payment`/`paid`/`alreadyPaid`), `FUTOPAY_*`/`GOOGLEPAY_*` buildConfig fields, payment strings (source + skip translations submodule).

## 5. Remove ACRA crash reporting
- Remove ACRA dependency + `ENABLE_ACRA*` buildConfig, `CrashLoggingApplication` init code (stable & playstore variants) and its manifest wiring, `strings-crash-reporting.xml`, `CrashLoggingApplication.logPreferences` calls, DevSettings "Copy logs" entry if it depends on it. Crash info remains logcat.

## 6. Restore settings screen as "Languages" (dictionaries only)
- Keep `Languages.kt`, strip all model references (transformer leftovers per §2; voice-model import remnants: "explore voice input models online", `voice_input_settings_change_models_subtitle` dead pointer).
- Re-add a "Languages" item to the settings home + navigator route (it currently only exists as the dead `addLanguage` route), managing layouts/dictionaries/import.

## 7. FUTO links → fork GitHub + attribution
- Help menu: point to `github.com/Giorgio-Germani/reventor-privacy-keyboard` (project + issues); drop FUTO website/Discord/Zulip entries.
- Credits: keep upstream attribution ("Based on FUTO Keyboard"), point contribute links at the fork repo; remove i18n/futo-org contribute buttons.
- Misc URL fixes: `BugViewer.kt` mailto, `ActionBar.kt` quick-clip `keyboard@futo.org` chip, `Setup.kt` nightly-docs link, `Swipe.kt` blog link, `ImportResourceActivity` dictionary catalog URL (keep dictionaries link only if we keep the online catalog — will keep the dictionary addon URL, it's generic infrastructure).
- Repo hygiene: delete FUTO CI scripts (`sendZulipMessage.sh`, `uploadNightly*.sh`, `setUpPropertiesCI.sh`), unused `futo_logo` drawable if unreferenced.

## 8. Fork overview document
- Write `FORK_NOTES.md`: what was removed and why, what was intentionally kept (applicationId `org.futo.inputmethod.latin` — renaming breaks installs/upgrades and hardcoded references; dictionary addon URLs; NOTICE attribution), and the remaining checklist to fully own the product (LICENSE.md is FUTO Source First License — needs a legal decision for redistribution; README rewrite; translations submodule still FUTO's; playstore/unstable flavor cleanup).

## 9. Build & verify
- `assembleStableDebug` + `assembleStableRelease` must pass (native build shrinks a lot with ggml/sentencepiece gone). Install debug on the device; check startup + settings menus + spacebar language switching sync; no crash / no FUTO-update surface remains.
- Commit + push to `fork` remote when green (commit only if you want — say the word; default: commit and push like last time).
