# Pushbound Monetization

**Status: Rewarded advertising implemented. Production ad serving not yet validated.**

This document describes the advertising system as it exists in this repository
today, the rules it is required to follow, and the design standard that applies
to future games built in the same ecosystem.

It documents Pushbound only. It says nothing about any other title.

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

## 4. When an ad may appear

- Only after the player taps **Hint** with the free allowance spent.
- Only after the player explicitly confirms the offer.
- Only in response to a player-initiated action.

## 5. When an ad must never appear

- During active puzzle solving, or interrupting a movement or a crate push.
- Covering the board unexpectedly.
- Immediately after launching the game.
- Automatically after completing a level.
- After every failed attempt or every button press.
- To start a level or to finish a level.
- Between levels, or as a permanent banner over the puzzle.
- As an app-open ad.

There is **no** interstitial, banner, native or app-open ad unit in this
product, and `noOtherAdFormatIsImplementedAnywhere` fails the build if one is
integrated. `AdsGateway.showInterstitial` exists only as a *seam* with no unit
behind it, so that a future decision still has to pass through
`InterstitialPolicy` — the policy decides, never the caller.

---

## 6. Offline behavior

Pushbound is fully playable with no connectivity.

The advertising SDK merges `android.permission.INTERNET`,
`android.permission.ACCESS_NETWORK_STATE` and
`com.google.android.gms.permission.AD_ID` into the shipped manifest through
ordinary manifest merging. **Pushbound never declares a permission itself** —
the network that exists exists only so that a player who explicitly chose to
watch an ad can be served one.

The guarantee therefore moved from the manifest to the code, where it is still
true:

- `noSourceOpensANetworkConnection` — no game source opens a connection;
- `pushboundItselfNeverRequestsAPermission` — the app asks for nothing;
- `theAdvertisingSeamItselfStaysPlatformFree` — the policy layer stays on a
  plain JVM.

Offline, a hint request past the free allowance reaches the consent step, cannot
resolve, and reports **unavailable**. The player sees a notice and keeps
playing. Campaign, Daily Puzzle and Resume are unaffected.

---

## 7. Failure and edge-case handling

Every rewarded outcome is delivered **exactly once** by `Delivery` inside
`AdMobAdsGateway`. A double delivery would grant two hints for one watch; a
missing one would leave the confirmation stuck forever.

| Situation | Outcome | Player impact |
| --- | --- | --- |
| Ad loaded and watched to the end | `REWARDED` | Hint granted |
| Player closes the ad early | `SKIPPED` | Notice; no hint |
| Player declines the consent form | `DISMISSED` | Notice; no hint; nothing lost |
| Nothing loaded / expired / show failed | `UNAVAILABLE` | "Your hint is untouched" |
| Fetch exceeds 10 s | `UNAVAILABLE` | Confirmation cannot hang |
| Offline / no network | `UNAVAILABLE` | Notice; play continues |
| No SDK configured (preview, test) | `UNAVAILABLE` | Identical code path |

An ad can never corrupt level progress, puzzle state, completion status,
medals, movement or push counts, or saved progress: the gateway's only
observable output is one outcome, and the only thing it can change is whether a
hint arrives.

**Consent (UMP)** is resolved lazily on the first confirmed offer rather than at
launch, so the game's opening frames are never interrupted. A consent form the
player closes reports `DISMISSED` — note that UMP reports an ordinary dismissal
as *no error*, so the signal used is "no form error **and** ads still may not be
requested".

---

## 8. Future: Remove Ads

The architecture is already shaped for a one-time **Remove Ads** purchase
through Google Play Billing, which is **not implemented and must not be added
unless explicitly requested**.

- Remove Ads is a **one-time purchase, not a subscription**.
- There is no virtual currency and no second monetization currency.

What a future implementation needs is a single predicate that answers "may ads
be shown?" at the `AdsGateway` boundary:

```
Free player        → plays normally, may voluntarily watch rewarded ads
Remove Ads player  → no advertising experiences at all
```

Because every call already goes through `AdsGateway`, adding that predicate
means changing one implementation — gameplay, the hint gate and the offer dialog
do not need to know a purchase happened. No rewrite of the gameplay system is
required.

---

## 9. The GameHub monetization standard

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

## 10. Architecture

```
UI (GameScreen / GameController)
        ↓  AdsGateway  (pure Kotlin, Android-free, unit-tested on the JVM)
RewardedAd controller / consent (ads/admob/)
        ↓
Google Mobile Ads + UMP SDK
```

- `app/src/main/java/com/pushbound/game/ads/` — the seam and all policy:
  `AdsGateway`, `HintGate`, `InterstitialPolicy`. **No Android imports**, so the
  entire monetization policy runs under plain JUnit.
- `app/src/main/java/com/pushbound/game/ads/admob/AdMobAdsGateway.kt` — the one
  class that may import Google's types.
- `MainActivity.kt` — the only place the SDK is initialized and the gateway is
  constructed. It is handed to `PushboundApp` and never reaches the engine.

The puzzle engine knows only that a reward was granted. It has never heard of
Google AdMob, and `noEnginePackageImportsAndroid` keeps it that way.

---

## 11. Production checklist — NOT YET DONE

**Honest status:** this implementation has been written, unit-tested and built
by CI, but the app has **never been run on a device or emulator**. No ad has
been shown, no reward has been observed end-to-end, and no production ad has
been served or validated.

Before publishing:

1. **Privacy Policy and Terms of Use must be updated.** They currently describe
   advertising as *not currently active* and *planned*. Activating AdMob means
   the app now loads advertising from Google, uses the advertising ID, and may
   collect data through Google's SDK under Google's own policies. Those pages
   must reflect that before release. This document does not invent privacy
   details — the actual data processing must be written from Google's
   published AdMob/Ump policy disclosures.
2. **Verify on a device or emulator**: the app starts without AdMob errors,
   the hint offer appears, a *test* ad plays, a reward is granted only on
   completion, cancelling grants nothing, a failed load does not block gameplay,
   and the game runs in airplane mode.
3. **Confirm consent behavior** for the regions you serve, and wire UMP's
   privacy options form if required.
4. **Register the production ad unit** in the AdMob console if it is not
   already live, and confirm the app's AdMob App ID matches the account that
   owns it.
5. **Do not claim production validation** from test-ad runs.

Nothing in this file should be read as a claim that production advertising has
been validated.
