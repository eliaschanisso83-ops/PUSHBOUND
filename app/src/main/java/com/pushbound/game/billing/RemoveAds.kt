package com.pushbound.game.billing

/**
 * THE COMPILE-TIME SEAM FOR THE FUTURE Remove Ads PRODUCT — and nothing else.
 *
 * [REMOVE_ADS_PRODUCT_ID] is EMPTY, ON PURPOSE, and it must stay empty until a
 * real product has been created by hand in Google Play Console.
 *
 * WHY IT IS NOT A PLACEHOLDER — a placeholder id such as `remove_ads` or
 * `test.remove_ads` would be a string this app would happily send to Google
 * Play as a real product, which is precisely the accident the rules forbid: it
 * would look, to the billing service and to any reviewer reading the source,
 * like a product that exists. An empty string cannot be mistaken for one, and
 * [isConfigured] is false for exactly as long as it is empty, so every path
 * that could reach Play is closed until a human supplies the value.
 *
 * WHERE THE REAL ID GOES WHEN IT EXISTS — here, in this one constant, after
 * Play Console has a one-time product to match it. Every other decision in
 * [RemoveAdsBilling] and [com.pushbound.game.billing.play.GooglePlayBillingGateway]
 * already reads this value rather than hard-coding an id, so supplying it is a
 * one-line change and the behaviour it unlocks is bounded by the code written
 * against it here.
 *
 * A product id is a PUBLISHABLE identifier rather than a secret, exactly like
 * the AdMob unit ids — but unlike those, this one does not exist yet, so there
 * is nothing to commit. No id is invented anywhere in this repository.
 */
object RemoveAdsProduct {

    /**
     * The Play Console product id for the one-time Remove Ads purchase.
     *
     * EMPTY MEANS UNCONFIGURED. See the type's documentation for why it is
     * deliberately not filled with a plausible-looking value.
     */
    const val REMOVE_ADS_PRODUCT_ID: String = ""

    /** True once a real Play Console product id has been supplied. */
    val isConfigured: Boolean get() = REMOVE_ADS_PRODUCT_ID.isNotBlank()
}

/**
 * The billing decisions that do NOT need Android — the two questions a
 * purchase flow must answer correctly before it is allowed to touch Play.
 *
 * Extracted from [com.pushbound.game.billing.play.GooglePlayBillingGateway] for
 * exactly the reason the ad layer keeps its rules out of the SDK: these are
 * the lines that decide whether something can be sold or owned, they must
 * never be wrong, and they are testable on the JVM without a device, a
 * service connection or a product in Play Console.
 */
object RemoveAdsBilling {

    /**
     * Whether a purchase attempt must be REFUSED before anything is sent to
     * Play, and why.
     *
     * @param configured a real Play Console product id is present.
     * @param owned the player is already believed to own the product.
     * @return null when the attempt may PROCEED, or the outcome to report
     *   instead. Refusal is expressed as a value rather than an exception so
     *   every caller has to handle it — a purchase flow that cannot buy must
     *   say so, not throw.
     *
     * CONNECTION IS DELIBERATELY NOT A PARAMETER, because these two refusals
     * are the ones that need no service: asking an unreachable Play about a
     * product that does not exist produces "unavailable" for a reason that has
     * nothing to do with the network, and answering that way would send a
     * caller retrying for something a human still has to create in Play
     * Console. Reachability is checked by the gateway, afterwards, so the
     * configuration check always wins.
     */
    fun refusePurchase(
        configured: Boolean,
        owned: Boolean
    ): RemoveAdsPurchaseOutcome? = when {
        !configured -> RemoveAdsPurchaseOutcome.NOT_CONFIGURED
        owned -> RemoveAdsPurchaseOutcome.ALREADY_OWNED
        else -> null
    }

    /**
     * Turns what Google Play reported into an entitlement — or does not.
     *
     * THE ENTITLEMENT IS DERIVED FROM PURCHASE STATE, never from a local
     * boolean: [purchasedProductIds] is what the billing service actually
     * returned for this install, and this function only decides whether that
     * answer counts as owning Remove Ads.
     *
     * Both guards matter and neither is redundant. An unconfigured build can
     * never be entitled, whatever a response contains, so a stale or spoofed
     * list cannot manufacture ownership before the product exists; and the id
     * has to be present in the response, so owning some OTHER product in this
     * app — or Play returning a list that merely mentions a similar string —
     * grants nothing.
     *
     * NOT SERVER VERIFIED. Google recommends verifying non-consumable
     * purchases server-side before granting entitlement. Pushbound has no
     * backend, by design, and building one is out of scope: this is the local
     * half of the check and the documented extension point for adding a
     * verifier later, without which Remove Ads must not be treated as
     * production-ready. See `docs/monetization.md`.
     */
    fun ownsRemoveAds(
        configured: Boolean,
        purchasedProductIds: Collection<String>
    ): Boolean =
        configured &&
            purchasedProductIds.any { it == RemoveAdsProduct.REMOVE_ADS_PRODUCT_ID }
}
