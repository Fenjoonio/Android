# Store billing: premium accounts (Bazaar, Myket, direct)

The app ships in three flavors. The flavor decides how premium is paid for:

| Flavor   | Payment                                   | SDK                                                |
|----------|-------------------------------------------|----------------------------------------------------|
| `direct` | ZarinPal on the web (one-time, N days)    | none                                               |
| `bazaar` | Bazaar subscriptions (can auto-renew)     | Poolakey `com.github.cafebazaar.Poolakey:poolakey` |
| `myket`  | Myket one-time passes (consumable, N days)| `ir.myket:billing` from `https://maven.myket.ir`   |

Myket sells passes because Myket does not support subscriptions or auto-renewal; its docs say to
model time-limited plans as consumables and manage expiry on your own server
(https://myket.ir/docs/technical/iab/faq). The backend verifies each pass with Myket's purchase
validation API and checks that its developer payload is the signed-in user's id.

## Products

The same product IDs exist in both store consoles:

| Product ID           | Days | Bazaar console price |
|----------------------|------|----------------------|
| `fenjoon_premium_1m` | 30   | 1,500,000 Rial       |
| `fenjoon_premium_3m` | 90   | 3,600,000 Rial       |
| `fenjoon_premium_6m` | 180  | 5,760,000 Rial       |
| `fenjoon_premium_1y` | 365  | 7,200,000 Rial       |

Bazaar: define them as subscriptions. Myket: define them as consumable in-app products.
The native bridge (`billing/BillingBridge.kt`) refuses any other product ID.

## Building

```
./gradlew assembleDirectRelease
./gradlew assembleBazaarRelease
./gradlew assembleMyketRelease -PfenjoonMyketPublicKey=<base64 RSA key from the Myket panel>
```

Debug builds: `assembleDirectDebug`, `assembleBazaarDebug`, `assembleMyketDebug`.
Keep `fenjoonMyketPublicKey` in `~/.gradle/gradle.properties`, not in this repository.
Bazaar needs no key in the app: Poolakey runs with `SecurityCheck.Disable` because the backend
verifies every token with Bazaar's developer API.

## How a purchase flows

1. The `/premium` page calls `POST /v1/premium/purchases {productId}`. The backend reads the
   channel from the user agent (`Fenjoon-WebView/<version> (store=<flavor>)`) and answers with
   `type: "subscription"` (Bazaar), `type: "inapp"` (Myket), or a ZarinPal `paymentUrl`.
2. Bazaar: `window.AndroidBilling.subscribe(productId, payload)`.
   Myket: `window.AndroidBilling.purchase(productId, payload)`.
   The result comes back through `window.__fenjoonBilling.onResult(json)`.
3. The page sends the token to `POST /v1/premium/store-purchases`. The backend verifies it with the
   store and grants premium. For Myket the page then calls `AndroidBilling.consume(token)` so the
   pass can be bought again.

If the user already owns the subscription (Bazaar) or an unconsumed pass (Myket), the bridge hands
back its token instead of opening a second purchase. Bazaar renewals are picked up by the backend
on its own (when the period ends and in a 6-hourly job).

## Backend env

`BAZAAR_API_TOKEN`, `MYKET_ACCESS_TOKEN`, `ANDROID_PACKAGE_NAME`, `ZARINPAL_MERCHANT_ID`.

## Known limitation

The Myket SDK (2.3) pulls `com.google.android.gms:play-services-ads-identifier:18.1.0`,
`androidx.browser:browser:1.8.0` and, through OkHttp 5, `androidx.startup:startup-runtime:1.2.0`.
Google's Maven must serve these to the machine that builds the `myket` flavor.
