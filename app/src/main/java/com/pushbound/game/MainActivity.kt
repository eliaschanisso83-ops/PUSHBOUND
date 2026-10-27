package com.pushbound.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.android.gms.ads.MobileAds
import com.pushbound.game.ads.admob.AdMobAdsGateway
import com.pushbound.game.billing.BillingGateway
import com.pushbound.game.billing.play.GooglePlayBillingGateway
import com.pushbound.game.ui.PushboundApp
import com.pushbound.game.ui.PushboundTheme

class MainActivity : ComponentActivity() {

    /**
     * The billing seam, owned by the shell.
     *
     * Never read by anything above this class, on purpose: a gateway that only
     * this activity touches cannot leak a purchase affordance into gameplay,
     * and there is no entitlement for anything to read because Pushbound sells
     * nothing.
     */
    private var billing: BillingGateway? = null

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

        // ---------------------------------------------------------------------
        // BILLING LIVES HERE, AND ONLY HERE — AND DOES NOTHING AT ALL YET.
        //
        // Pushbound has NO product for sale. `RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID`
        // is an empty string, so this gateway refuses a purchase before it ever
        // contacts Google Play, and no screen anywhere in the app asks the
        // player to buy anything: the future one-time "Remove Ads" purchase does
        // not exist until a human creates it in Google Play Console.
        //
        // It is CONSTRUCTED BUT NEVER ASKED TO CONNECT. Nothing queries it, so
        // no billing service is bound at launch, no request is made, and the
        // app's observable behaviour is exactly what it was before the
        // dependency was added — no ad, no dialog, no entitlement and no
        // gameplay path changed with it. Holding it here rather than inside the
        // composition is about ownership: the activity that creates it is the
        // activity that closes it, and the seam is already in place for the day
        // a real product id arrives.
        //
        // The proof of "prepared but inactive" is blunt — deleting this line,
        // the two imports and the `billing` package would change nothing any
        // player can see, and would not change how a single level plays.
        // ---------------------------------------------------------------------
        billing = GooglePlayBillingGateway(this)

        setContent {
            PushboundTheme {
                // The root owns navigation: Main Menu → Campaign → World →
                // Level → Game, plus the daily-puzzle entry.
                PushboundApp(ads = ads)
            }
        }
    }

    override fun onDestroy() {
        // Released rather than abandoned: it costs nothing while disconnected,
        // and an SDK object that outlives the activity it was built from is
        // how a leaked window ends up in a heap dump later.
        billing?.close()
        billing = null
        super.onDestroy()
    }
}
