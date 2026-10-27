# Pushbound Monetization

**Status: Rewarded advertising implemented, for two rewards — hints and Undo
restoration. Google Play Billing infrastructure present but INACTIVE: no
product exists and nothing is for sale.**

This document describes the monetization system as it exists in this repository
today, the rules it is required to follow, and the design standard that applies
to future games built in the same ecosystem.

It documents Pushbound only. It says nothing about any other title.

---

## What is CURRENT and what is FUTURE

Two things in this repository sound similar and are not. Keeping them apart is
the whole point of the table below, because conflating them is exactly how a
build ends up advertising a product it cannot sell.

| | **CURRENT — shipped in the app now** | **FUTURE — prepared, not for sale** |
| --- | --- | --- |
| What it is | Rewarded ads for two rewards: a **hint**, and **5 restored Undo moves** | A one-time **Remove Ads** purchase |
| Player can do it? | Yes, by tapping Hint or Undo and choosing to watch | **No.** There is no product id, no buy button and no screen that mentions it |
| Code status | Implemented, unit-tested, built by CI | Seam, policy and SDK gateway written; `REMOVE_ADS_PRODUCT_ID` is deliberately empty |
| Play Console | Ad units registered (see §1) | **Google Play Console product creation is still required before Remove Ads can be sold.** |
| Legal pages | Must describe rewarded advertising as active — see §12 | Must describe Remove Ads as *planned*; must **not** claim billing is active |

> **Google Play Console product creation is still required before Remove Ads
> can be sold.**

Nothing in the FUTURE column is purchasable today, and this document never
describes it as though it were.

---

## 1. Current AdMob configuration

| Item | Value |
| --- | --- |
| AdMob App ID (release) | `ca-app-pub-5147549053838908~5350410819` |
| Rewarded ad unit (release) | `ca-app-pub-5147549053838908/7178497807` |
| Ad unit name | `Pushbound_Rewarded_Hint` |
| AdMob App ID (debug) | `ca-app-pub-3940256099942544~3347511713` (Google demo) |
| Rewarded unit (debug) | `ca-app-pub-3940256099942544/5224354917` (Google demo) |
| SDK artifacts | `com.google.android.gms:play-services-ads:24.4.0`, `com.google.android.ump:user-messaging-platform:3.2.0` |
| Formats implemented | **Rewarded only** |

**These identifiers are not secrets.** An AdMob App ID and an ad unit ID are
compiled into every APK and can be read from it by anyone who downloads the
app. Google documents them as values you are expected to ship. They are
therefore held in a versioned Gradle file on purpose, and they are entirely
separate from the signing material, which never appears in the repository.

### Why there are two pairs

Development and QA use **Google's own demo App ID and demo rewarded unit**, so
the whole flow can be exercised as often as anyone likes without ever touching
live inventory or risking invalid activity on the production account.

The switch is fail-safe: `defaultConfig` sets the demo values and only the
`release` build type overrides them. A build type that is added later and
forgets to configure ads gets the demo pair by default — it cannot reach
production by omission.

The test `developmentBuildsNeverPointAtProductionAdInventory` pins this, and
`theOnlyAdUnitsInTheProductAreTheRewardedPair` pins that no third ad unit is
ever added.

### Why the AdMob SDK is in maintenance mode, and why 24.4.0

Google has moved new development to the "GMA Next-Gen SDK" and marks the
classic `play-services-ads` line as being in maintenance. No Next-Gen artifact
is published on Google's standard Android Maven index at the time of writing,
so this project uses the classic SDK, whose Rewarded API is stable and
documented. This should be revisited before a major new ad feature is added.

The SDK is pinned to **24.4.0** for a hard, measurable reason rather than a
preference. Pushbound compiles with Kotlin **2.0.21**, which reads Kotlin
binary metadata up to version 2.0.0 and no further:

| SDK version | Kotlin metadata | Result |
| --- | --- | --- |
| 25.5.0 | 2.3.0 | fails to compile |
| 24.9.0 | 2.2.0 | fails to compile |
| 24.6.0 | ≤ 2.0.0 | compiles, but depends on `kotlin-stdlib:2.1.0` |
| **24.4.0** | ≤ 2.0.0 | **compiles, no Kotlin dependency** |

24.6.0 compiles on its own but declares `kotlin-stdlib:2.1.0`, which Gradle's
version resolution would place back on the classpath and reintroduce the same
incompatible metadata. 24.4.0 is therefore the newest release that is
compatible with this toolchain end to end.

This was verified by compiling the real `AdMobAdsGateway.kt` against each
candidate with the project's own vendored Kotlin compiler — not by inference.

**The ceiling is the compiler, not the ad SDK.** Raising the Kotlin version is
what would unlock a newer SDK, and that is a toolchain decision to be made on
its own merits rather than as a side effect of adding advertising.

---

## 2. The core principle: optional value exchange

```
PLAYER CHOOSES ACTION
        ↓
OPTIONAL REWARDED AD
        ↓
PLAYER RECEIVES USEFUL REWARD
        ↓
GAMEPLAY CONTINUES
```

The advertisement is attached to a **voluntary decision by the player**. The
player must always understand, *before* committing:

- what they will receive;
- that an advertisement will be shown;
- that watching it is optional;
- what happens if they cancel or decline.

An advertisement is never disguised as a normal gameplay action.

---

## 3. The rewarded hint flow

When the player taps **Hint** past the free allowance:

1. **No ad is opened.** `GameController.requestHint()` only *raises an offer*
   (`rewardedHintOffer`); it never calls into the SDK.
2. A confirmation dialog appears — `RewardedHintDialog` in `GameScreen.kt` —
   stating the reward first ("Watch a short ad to get one more hint"), that a
   short ad will be shown, and that it is optional.
3. **"Not now"** is an equally sized, equally reachable button. Declining
   charges nothing, grants nothing and changes nothing.
4. **Only** `GameController.confirmRewardedHint()`, reached by an explicit
   confirm, calls `AdsGateway.showRewarded(...)`.
5. After `RewardedOutcome.REWARDED` the hint is granted and the free allowance
   is untouched.
6. Any other outcome grants nothing.

> Loading also happens only after the player confirms. Asking for a hint is not
> yet choosing to watch an ad, so no fetch starts until they say yes.

### What a hint is

The reward is a hint, and a hint **assists rather than solves**. Pushbound's
three escalating tiers are:

1. the direction of the next useful push;
2. which crate matters, and which way it must go;
3. the single exact next move.

Tier 3 reveals **one** move. After taking it the player is on their own again.
Hints are derived from the state the player is actually in, never from a cached
solution, and a hint that cannot be computed is withheld rather than guessed.
No ad can reveal a full solution.

### The free allowance

Three free hints per attempt, granted with no ad and no friction. The allowance
resets on restart or on opening a level, so a stuck player can never hit a wall
that stops them finishing. An ad-backed hint is counted in its **own** column
and never eats the free allowance — a player who watches an ad must not come out
worse than one who does not.

---

## 4. The rewarded Undo restoration

Undo is a **resource with a balance of five**, and the balance is the only
thing that ever gates it. Nothing else — not ads, not hints, not progression,
not which mode is being played.

### The credit rules

| Rule | Value |
| --- | --- |
| Starting balance | 5 |
| Maximum balance | 5 |
| Cost of one **successful** Undo | 1 |
| Balance at zero | 0 — never negative |
| Rewarded restorations per local calendar day | 3 |

- The charge is computed **before** the engine is asked and committed **only**
  if the engine says yes. An undo that is refused — no history to walk back, an
  animation still running, a session just restored — costs nothing, because the
  rules charge for the engine's answer rather than for the tap.
- The balance is **player-scoped**, not level-scoped and not mode-scoped. It
  survives a restart, a level change, a move between modes, a configuration
  change and a process death. Nothing in `restart()` or `loadLevel()` resets it.
- It is persisted to its own `SharedPreferences` file (`pushbound_undo`, key
  `undo_credits_v1`) as `credits|restorationsToday|lastResetEpochDay`. A
  malformed payload is **refused rather than guessed**, which hands the player
  their five back instead of a number nobody chose; an out-of-range balance is
  clamped into range; and fields appended by a future build are ignored, so an
  update never resets a returning player.

### The offer, and the one line that reaches the SDK

At a balance of zero the player taps Undo and is **asked**, not acted for:

1. **No ad is opened.** `GameController.undo()` only *raises an offer*
   (`undoRestorationOffer`); it never calls into the SDK, and it does not even
   preload.
2. A confirmation dialog appears — `UndoRestorationDialog` in `GameScreen.kt` —
   titled **"Out of Undo moves"**, stating the reward first ("Watch a short
   rewarded ad to restore 5 Undo moves"), that it is optional, and that nothing
   about progress or balance changes if they decline.
3. **"Not now"** is an equally sized, equally reachable button. Declining
   charges nothing, grants nothing, requests no ad and starts no timer.
4. **Only** `GameController.confirmUndoRestoration()`, reached by the button
   that says **"Watch Ad"**, calls `AdsGateway.showRewarded(...)` — the same
   seam, the same exactly-once `Delivery`, the same consent handling as the
   hint flow.
5. After `RewardedOutcome.REWARDED` the balance becomes **5**, counted once
   against today's allowance.
6. **Every other outcome grants nothing** and is reported as a one-line status
   — never an error screen, never a blocked board, never a retry demand.

The exactly-once guard sits **inside** the callback rather than being trusted to
the gateway: the first delivery clears the pending flag and the offer, so a
duplicate reward callback finds them already cleared and returns with the
balance still at five instead of topping it up to ten.

### The daily allowance

Three successful rewarded restorations per **local calendar day**, counting
**successes only**. Opening the offer, dismissing it, or failing to fetch an ad
consumes nothing.

- A fourth attempt of the same day is refused **before the offer is raised**, so
  no ad is ever requested for a restoration that cannot happen.
- When midnight passes, `restorationsToday` is zeroed and the **balance is not**.
  Unused moves carry over; a player holding two at midnight holds two in the
  morning. A rollover refills the *ad allowance*, never the Undo balance — the
  player earns the five back.
- The roll-forward runs at every entry point that can spend or grant, from the
  real clock, with no timer and no background work. It is idempotent, so calling
  it twice changes nothing.

### What is deliberately NOT here

- **No login, no backend, no account.** The balance is a local value.
- **No interstitial, banner, native or app-open ad.** The same single Rewarded
  unit serves both rewards.
- **No ad at launch, mid-move, over the board, after a level or failure, on
  returning to the app, or merely because Undo is unused.**
- **No error screen.** A failed or offline restoration leaves the balance at
  zero, shows one sentence, and play continues uninterrupted.

### Accessibility

The control announces its state rather than its glyph, from the same balance the
chip draws: **"Undo, 5 moves remaining"** counting down, and **"Undo unavailable,
no moves remaining"** at zero. The chip itself is stripped from the
accessibility tree so the count is never read twice. Pushbound has no
localization layer (`strings.xml` holds only `app_name`), so this English copy
matches the rest of the shipped UI.

### What pins it

`UndoCreditsTest` covers the arithmetic on a plain JVM — start at five, five to
zero, refuse the sixth, three restorations then refused, midnight resets the
count and only the count, codec round-trip and corruption handling. See
`UndoRestorationFlowTest` for the player-facing half — that a tap at zero opens
the offer without requesting an ad, that declining changes nothing, that only
`REWARDED` grants, that a duplicate callback pays five and not ten, and that
level boundaries and process death touch neither balance nor allowance. See
`UndoUiPresentationTest` for the wiring and the copy.

---

## 5. When an ad may appear

- Only after the player taps **Hint** with the free allowance spent.
- Only after the player taps **Undo** with a zero balance.
- Only after the player explicitly confirms the offer — the button that says
  **"Watch Ad"**. Opening an offer loads nothing.
- Only in response to a player-initiated action.

There are exactly two confirmations in the whole app, and `noScreenAsksForAnAdOutsideTheTwoConfirmationsThatAlreadyExist`
fails if a third appears.

## 6. When an ad must never appear

- During active puzzle solving, or interrupting a movement or a crate push.
- Covering the board unexpectedly.
- Immediately after launching the game.
- Automatically after completing a level.
- After every failed attempt or every button press.
- To start a level or to finish a level.
- Between levels, or as a permanent banner over the puzzle.
- As an app-open ad.
- **Merely because Undo is unused** — an unused balance is not an event, and a
  zero balance is an offer, never an unrequested fetch.
- **To attempt a fourth rewarded restoration on the same day** — the allowance
  is refused before the offer exists, so nothing is requested at all.

There is **no** interstitial, banner, native or app-open ad unit in this
product, and `noOtherAdFormatIsImplementedAnywhere` fails the build if one is
integrated. `AdsGateway.showInterstitial` exists only as a *seam* with no unit
behind it, so that a future decision still has to pass through
`InterstitialPolicy` — the policy decides, never the caller.

---

## 7. Offline behavior

Pushbound is fully playable with no connectivity.

The advertising SDK merges `android.permission.INTERNET`,
`android.permission.ACCESS_NETWORK_STATE` and
`com.google.android.gms.permission.AD_ID` into the shipped manifest through
ordinary manifest merging, and the billing library merges
`com.android.vending.BILLING` the same way. **Pushbound never declares a
permission itself** — the network that exists exists only so that a player who
explicitly chose to watch an ad can be served one. `BILLING` is a normal-level
permission with no runtime prompt; it states that billing is in use and asks for
nothing from the player.

The guarantee therefore moved from the manifest to the code, where it is still
true:

- `noSourceOpensANetworkConnection` — no game source opens a connection;
- `pushboundItselfNeverRequestsAPermission` — the app asks for nothing;
- `theAdvertisingSeamItselfStaysPlatformFree` — the policy layer stays on a
  plain JVM;
- `theBillingSeamItselfStaysPlatformFree` — so does the billing policy.

Offline, a hint request past the free allowance reaches the consent step, cannot
resolve, and reports **unavailable**. The player sees a notice and keeps
playing. Campaign, Daily Puzzle and Resume are unaffected.

**Undo is unaffected by connectivity entirely.** The balance, the history walk
and the level are all local, so an offline player undoes exactly as an online
one does. What offline costs is only the *restoration*: the offer still opens,
the ad cannot be fetched, the outcome is `UNAVAILABLE`, and the balance stays
where it was — with one sentence and no interruption.

---

## 8. Failure and edge-case handling

Every rewarded outcome is delivered **exactly once** by `Delivery` inside
`AdMobAdsGateway`. A double delivery would grant two hints — or two sets of five
Undo moves — for one watch; a missing one would leave the confirmation stuck
forever. Both rewards travel the same seam, the same consent handling and the
same exactly-once delivery, so there is one set of failure semantics to reason
about rather than two.

The table below is stated for the hint; the Undo restoration behaves
identically, with "hint granted" read as "**balance becomes 5, counted once
against today's allowance**", and "no hint" read as "**balance stays where it
was, with one sentence and no interruption**".

| Situation | Outcome | Player impact |
| --- | --- | --- |
| Ad loaded and watched to the end | `REWARDED` | Hint granted |
| Player closes the ad early | `SKIPPED` | Notice; no hint |
| Player declines the consent form | `DISMISSED` | Notice; no hint; nothing lost |
| Nothing loaded / expired / show failed | `UNAVAILABLE` | "Your hint is untouched" |
| Fetch exceeds 10 s | `UNAVAILABLE` | Confirmation cannot hang |
| Offline / no network | `UNAVAILABLE` | Notice; play continues |
| No SDK configured (preview, test) | `UNAVAILABLE` | Identical code path |

For Undo there are two further rules the hint flow does not need:

- **The exactly-once guard is re-checked inside the controller's callback**, not
  trusted to the gateway: the first delivery clears the pending flag and the
  offer, so a duplicate callback finds them already cleared and returns with the
  balance still at five rather than ten. `aDuplicateRewardCallbackGrantsFiveAndNeverTen`
  delivers the same callback twice on purpose to prove it.
- **An in-flight offer cannot be declined out from under the ad.** "Not now" is
  refused while a confirmed ad is running, because dropping the pending flag
  there would make the real reward look stale and throw it away — leaving the
  player having watched for nothing.

An ad can never corrupt level progress, puzzle state, completion status,
medals, movement or push counts, or saved progress: the gateway's only
observable output is one outcome, and the only thing it can change is whether a
hint arrives or the balance moves between 0 and 5. **The engine remains the
authority for every move** — the credit system only gates whether a request is
made, never whether it is legal.

**Consent (UMP)** is resolved lazily on the first confirmed offer rather than at
launch, so the game's opening frames are never interrupted. A consent form the
player closes reports `DISMISSED` — note that UMP reports an ordinary dismissal
as *no error*, so the signal used is "no form error **and** ads still may not be
requested".

---

## 9. FUTURE: Remove Ads — prepared, and NOT FOR SALE

> **Google Play Console product creation is still required before Remove Ads
> can be sold.**

Pushbound ships **no product for sale**. Remove Ads is a **one-time purchase,
not a subscription**, and it does not exist yet: there is no product id, no buy
button, no screen that mentions it, and no entitlement anything could hold.
There is no virtual currency and no second monetization currency.

### What IS in the repository today (inactive)

The infrastructure is written so that adding the feature later is a bounded,
reviewable change rather than a new subsystem — but **none of it is wired to
anything a player can reach**.

| Piece | Where | State |
| --- | --- | --- |
| `BillingGateway` seam + `NoOpBillingGateway` default | `billing/BillingGateway.kt` | Written, Android-free, unit-tested |
| `RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID` | `billing/RemoveAds.kt` | **`""` — deliberately empty** |
| `RemoveAdsBilling` refusals and ownership rule | `billing/RemoveAds.kt` | Written, unit-tested |
| `GooglePlayBillingGateway` | `billing/play/` | Written, compiled by CI; **never asked to connect** |
| Play Billing client `com.android.billingclient:billing:8.3.0` | `app/build.gradle.kts` | Declared and merged |
| `com.android.vending.BILLING` permission | via library manifest merge | Present in the built APK; **absent from the source manifest by design** |

The activity constructs the gateway and closes it with itself. Nothing queries
it, so no billing service is bound at launch and **no observable behaviour
changed when the dependency was added** — no ad, no dialog, no entitlement and
no gameplay path. Deleting the `billing` package, its dependency and the two
lines in `MainActivity.kt` would change nothing a player can see.

### What is deliberately NOT here

- **No product id.** `REMOVE_ADS_PRODUCT_ID` is an empty string, not a
  placeholder. A value such as `remove_ads` would be a string the app would
  happily send to Google Play and that every reader of the source would believe
  is a product. `isConfigured` is false for exactly as long as it is empty, and
  `RemoveAdsBilling.refusePurchase` answers `NOT_CONFIGURED` before anything is
  contacted.
- **No purchase UI.** No screen reads the seam, no button says "Buy", and
  `noPurchaseAffordanceIsExposedToThePlayer` fails if one appears.
- **No fake entitlement.** `isRemoveAdsOwned` is false and can only become true
  from what Google Play actually reported; `ownsRemoveAds` requires both a
  configured product **and** an exact id match in the response, so an unconfigured
  build can never be entitled whatever a response contains.
- **No server-side verification.** Google recommends verifying non-consumable
  purchases server-side. Pushbound has no backend by design, so this is the
  local half only — which is why Remove Ads **must not be treated as
  production-ready** until a verifier exists.
- **No `mayAdsBeShown()` predicate** at the `AdsGateway` boundary yet.

### What a future implementation must ship together

- the product id, filled in here after Play Console has a one-time product to
  match it;
- the product itself, created in Play Console against an **unused, higher
  versionCode**;
- the `mayAdsBeShown()` predicate at the `AdsGateway` boundary — one
  implementation changes, gameplay does not;
- **an update to the legal pages.** Pushbound's current Terms of Use and
  Privacy Policy state that Remove Ads is **planned only** and that there are no
  in-app purchases. Those sentences stop being true the moment this ships, so
  both pages must be revised together with the code — the same rule that
  governed the rewarded-advertising release above.

### What Google Play requires before a one-time product can exist

Play will not let you create a one-time product until the uploaded APK carries
the `com.android.vending.BILLING` permission; until then it reports that the
application has no in-app products and that one is needed to add them.

**That permission now arrives automatically** from `com.android.billingclient:
billing:8.3.0` through normal manifest merging — which is why §7 still says the
app declares no permission itself, and why `app/src/main/AndroidManifest.xml`
must stay free of a hand-written `<uses-permission>` element. Declaring it by
hand would be a second source of truth for a permission that already arrives,
and it would survive removing the dependency as a permission nobody needs. As a
normal-level permission it adds no runtime prompt; it simply states that billing
is in use.

**The permission is not the product.** Having it in the APK does not make
anything purchasable, and this repository does not claim otherwise.

### Why the client is pinned to 8.3.0

The version was chosen against this toolchain rather than for recency:

| Client | Why not |
| --- | --- |
| 9.0.0 / 9.1.0 | Pull `androidx.core:core:1.15.0`, which Gradle resolves over this app's pinned `core-ktx:1.13.1` — an unrelated AndroidX upgrade |
| 8.3.0 | **Chosen.** Its only AndroidX dependency is `activity:1.2.3`, already exceeded by `activity-compose:1.9.0` |

All candidates compiled under Kotlin 2.0.21 with no metadata error and none
declares `kotlin-stdlib`; the transitive graph adds no permission beyond
`com.android.vending.BILLING` itself. The Kotlin/AGP/Gradle/Compose versions
and `compileSdk`/`targetSdk` were **not** raised to accommodate it, and
`theBillingDependencyIsPinnedAndTheToolchainAroundItIsNot` fails if they are.

---

## 10. The GameHub monetization standard

> **Gameplay first. Monetization second.**

This is the reusable principle for future games in this ecosystem. The rule is
the *principle*, not Pushbound's exact reward — different genres need different
rewards.

1. Ads should have a clear purpose.
2. Voluntary rewarded advertising is preferred when a meaningful reward exists.
3. Ads should be connected to a player decision whenever possible.
4. Do not interrupt the player's core gameplay unnecessarily.
5. Never design artificial frustration solely to increase ad views.
6. Never require advertisements for basic gameplay unless the game's design
   explicitly calls for it and the experience remains fair.
7. The reward must be clear before the player chooses to watch.
8. The player must be able to decline.
9. Ads must never compromise saved progress or gameplay state.
10. Monetization must remain secondary to the quality of the game.

### Choosing the reward by genre

| Genre | Suitable rewards |
| --- | --- |
| Puzzle | hint, limited assistance, optional extra attempt |
| Arcade | revive, continue, bonus attempt |
| Strategy | limited temporary assistance, optional information, non-essential bonus |

Do not copy one reward mechanic into every game. Difficulty must come from
design — planning, positioning, bottlenecks, access dependency, crate order,
deadlock avoidance, limited maneuvering space, irreversible decisions — **never**
from manufacturing frustration to sell hints. Ads are monetization layered on
top of good gameplay; they are not part of the difficulty system.

---

## 11. Architecture

```
UI (GameScreen / GameController)
        ↓  AdsGateway  (pure Kotlin, Android-free, unit-tested on the JVM)
RewardedAd controller / consent (ads/admob/)
        ↓
Google Mobile Ads + UMP SDK

UI (nothing — the seam is never read)
        ↓  BillingGateway  (pure Kotlin, Android-free, unit-tested on the JVM)
GooglePlayBillingGateway (billing/play/)
        ↓
Google Play Billing client 8.3.0
```

- `app/src/main/java/com/pushbound/game/ads/` — the seam and all policy:
  `AdsGateway`, `HintGate`, `InterstitialPolicy`. **No Android imports**, so the
  entire monetization policy runs under plain JUnit.
- `app/src/main/java/com/pushbound/game/ads/admob/AdMobAdsGateway.kt` — the one
  class that may import Google's advertising types.
- `app/src/main/java/com/pushbound/game/undo/` — `UndoGate`, `UndoCreditState`,
  `UndoCreditStore` and the codec. **No Android imports**, which is why the
  balance, the daily allowance and the midnight rollover are testable at all.
- `app/src/main/java/com/pushbound/game/billing/` — the seam, the no-op default
  and the Remove Ads decisions. **No Android imports**; only
  `billing/play/GooglePlayBillingGateway.kt` may import Google's billing types,
  and `theGooglePlayBillingClientIsImportedOnlyByItsOwnGateway` fails if that
  stops being true.
- `app/src/main/java/com/pushbound/game/data/SharedPreferencesUndoCreditStore.kt`
  — the one adapter that persists the balance.
- `MainActivity.kt` — the only place either SDK is initialised and either
  gateway is constructed. The ads gateway is handed to `PushboundApp`; the
  billing gateway is held and closed by the activity and **handed to nobody**.

The puzzle engine knows only that a reward was granted. It has never heard of
Google AdMob, Google Play Billing or a product id, and
`noEnginePackageImportsAndroid` — which now also covers `undo/` — keeps it that
way. `onlyTheDataPackageTouchesAndroid` confines platform imports to `ui/`,
`data/`, `ads/admob/`, `billing/play/` and `MainActivity.kt`.

---

## 12. Production checklist — NOT YET DONE

**Honest status:** this implementation has been written, unit-tested and built
by CI, but the app has **never been run on a device or emulator**. No ad has
been shown, no reward has been observed end-to-end, no Undo restoration has been
watched on a real screen, and no production ad has been served or validated.
Nothing below is a claim that any of those have happened.

### Legal pages

1. **Privacy Policy and Terms of Use must describe the CURRENT rewarded
   purpose accurately.** They must stop describing advertising as *not currently
   active* or *planned*: the app now loads advertising from Google, uses the
   advertising ID, and offers **two** rewarded rewards — a hint and **five
   restored Undo moves** — each shown only after the player chooses to watch.
   Activating AdMob means data may be collected through Google's SDK under
   Google's own policies. This document does not invent privacy details — the
   actual data processing must be written from Google's published AdMob/UMP
   policy disclosures.
2. **They must NOT claim billing is active.** Pushbound has no product for
   sale; Remove Ads remains *planned*. The pages must not imply an in-app
   purchase exists, and must not claim Google Play Billing is in use for
   selling anything.

### Before publishing

3. **Verify on a device or emulator**: the app starts without AdMob errors; the
   hint offer appears; a *test* ad plays; a reward is granted only on
   completion; cancelling grants nothing; a failed load does not block gameplay;
   the game runs in airplane mode.
4. **Verify Undo on a device**: the control shows `5` down to `0`, the offer
   opens only at zero, "Watch Ad" is the only thing that fetches an ad, "Not now"
   changes nothing, a completed ad restores **5** and increments today's count,
   a fourth restoration the same day is refused with a sentence rather than an
   ad, the balance survives leaving and reopening the app, and the screen reader
   announces "Undo, 5 moves remaining" and then "Undo unavailable, no moves
   remaining".
5. **Confirm consent behavior** for the regions you serve, and wire UMP's
   privacy options form if required.
6. **Register the production ad unit** in the AdMob console if it is not
   already live, and confirm the app's AdMob App ID matches the account that
   owns it.

### Google Play Console (manual, outside this repository)

7. **Google Play Console product creation is still required before Remove Ads
   can be sold.** No product has been created, none is named in this repository,
   and production purchase testing has not been claimed or performed.
8. **Upload the current AAB** — `versionCode 2`, `versionName 1.0.1`,
   `targetSdk 36` — as a new release, to a track that has never carried this
   versionCode.
9. **Complete the Data safety form** for the advertising SDK and the
   advertising ID, matching what Google's own AdMob/UMP disclosures say.
10. **Declare the ad personalization setting** — personalised versus
    non-personalised — consistently with the consent configuration actually
    shipped.
11. **Set the age rating and target audience** consistently with UMP's
    child-directed handling.

### The rule that does not change

12. **Do not claim production validation** from test-ad runs.

Nothing in this file should be read as a claim that production advertising has
been validated, that Remove Ads can be purchased, or that any in-app product
exists.
