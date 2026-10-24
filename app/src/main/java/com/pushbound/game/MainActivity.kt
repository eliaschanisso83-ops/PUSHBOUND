package com.pushbound.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.android.gms.ads.MobileAds
import com.pushbound.game.ads.admob.AdMobAdsGateway
import com.pushbound.game.ui.PushboundApp
import com.pushbound.game.ui.PushboundTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ---------------------------------------------------------------------
        // ADVERTISING LIVES HERE, AND ONLY HERE.
        //
        // This is the application shell, so it is the correct place for the two
        // things the SDK needs: one-time initialisation, and the construction of
        // the single [AdsGateway] the whole game is handed. Everything below
        // this line — navigation, the controller, the puzzle engine — receives
        // that interface and never imports an AdMob type.
        //
        // Initialisation is asynchronous and non-blocking: it prepares the SDK,
        // it does NOT fetch an ad and it does NOT show one. No ad appears at
        // launch, after a level, or between attempts — an ad is only ever
        // requested by a player confirming the hint offer.
        // ---------------------------------------------------------------------
        // The listener is ignored deliberately: there is no state to react to.
        // Initialisation prepares the SDK; whether it succeeded shows up later
        // as an unavailable ad, which the hint flow already handles gracefully.
        MobileAds.initialize(this) { _ -> }

        // Build-type aware: debug builds are wired to Google's demo App ID and
        // demo rewarded unit by app/build.gradle.kts, so development and QA can
        // tap the flow as often as they like without touching live inventory or
        // risking invalid activity on the production account.
        val ads = AdMobAdsGateway(this, BuildConfig.REWARDED_AD_UNIT_ID)

        setContent {
            PushboundTheme {
                // The root owns navigation: Main Menu → Campaign → World →
                // Level → Game, plus the daily-puzzle entry.
                PushboundApp(ads = ads)
            }
        }
    }
}
