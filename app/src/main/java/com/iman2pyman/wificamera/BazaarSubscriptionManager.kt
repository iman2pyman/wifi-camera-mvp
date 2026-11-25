package com.iman2pyman.wificamera

import androidx.appcompat.app.AppCompatActivity
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest

/**
 * Cafe Bazaar subscription manager.
 */
class BazaarSubscriptionManager(
    private val activity: AppCompatActivity,
    private val onStatus: (Boolean, String) -> Unit,
    private val onPrices: (String) -> Unit,
    private val onProducts: (Boolean, Boolean) -> Unit
) {
    private val payment = Payment(
        context = activity,
        config = PaymentConfiguration(
            localSecurityCheck = SecurityCheck.Enable(rsaPublicKey = RSA_PUBLIC_KEY),
            shouldSupportSubscription = true
        )
    )

    private var connection: Connection? = null
    @Volatile private var connected = false
    @Volatile private var annualProductAvailable = false
    @Volatile private var trialProductAvailable = false

    fun connect() {
        if (connection != null) return
        connection = payment.connect {
            connectionSucceed {
                connected = true
                refresh()
                refreshSubscriptionPrices()
            }
            connectionFailed { error ->
                connected = false
                onStatus(false, "اتصال به پرداخت کافه‌بازار برقرار نشد: ${error.message ?: "خطای نامشخص"}")
            }
            disconnected {
                connected = false
                onStatus(false, "ارتباط پرداخت کافه‌بازار قطع شد؛ برای بررسی دوباره تلاش کنید.")
            }
        }
    }

    fun refresh() {
        if (!connected) {
            onStatus(false, "برای بررسی اشتراک، ابتدا کافه‌بازار را نصب و وارد حساب خود کنید.")
            return
        }
        payment.getSubscribedProducts {
            querySucceed { subscriptions ->
                val active = subscriptions.isNotEmpty()
                onStatus(
                    active,
                    if (active) "اشتراک شما در کافه‌بازار فعال است."
                    else "اشتراک فعال پیدا نشد؛ می‌توانید دوره آزمایشی یا اشتراک یک‌ساله را تهیه کنید."
                )
            }
            queryFailed { error ->
                onStatus(false, "بررسی اشتراک ناموفق بود: ${error.message ?: "خطای نامشخص"}")
            }
        }
    }


    /**
     * Fetch subscription prices from Cafe Bazaar; never hardcode product prices.
     */
    private fun refreshSubscriptionPrices() {
        if (!connected) {
            annualProductAvailable = false
            trialProductAvailable = false
            onProducts(false, false)
            onPrices("برای دریافت اطلاعات اشتراک، اتصال کافه‌بازار برقرار نیست.")
            return
        }
        payment.getSubscriptionSkuDetails(
            skuIds = listOf(SUBSCRIPTION_SKU, TRIAL_SUBSCRIPTION_SKU)
        ) {
            getSkuDetailsSucceed { details ->
                val annual = details.firstOrNull { it.sku == SUBSCRIPTION_SKU }
                val trial = details.firstOrNull { it.sku == TRIAL_SUBSCRIPTION_SKU }
                val annualDigits = annual?.price?.toString()?.filter { it.isDigit() } ?: ""
                val annualNumericPrice = annualDigits.mapNotNull { it.digitToIntOrNull() }
                    .fold(0L) { acc, digit -> acc * 10 + digit }
                annualProductAvailable = annual != null && annualNumericPrice > 0L
                trialProductAvailable = trial != null
                onProducts(annualProductAvailable, trialProductAvailable)
                val annualText = when {
                    annual == null -> "اشتراک سالانه در کافه‌بازار ثبت/قابل‌دسترسی نیست"
                    annualNumericPrice <= 0L -> "قیمت معتبر اشتراک سالانه در کافه‌بازار موجود نیست"
                    else -> "قیمت اشتراک ۳۶۵ روزه: ${annual.price}"
                }
                val trialText = if (trial == null) "دوره آزمایشی در کافه‌بازار ثبت/قابل‌دسترسی نیست"
                    else "قیمت دوره آزمایشی: ${trial.price}"
                onPrices(listOf(annualText, trialText).joinToString("\n"))
            }
            getSkuDetailsFailed { error ->
                annualProductAvailable = false
                trialProductAvailable = false
                onProducts(false, false)
                onPrices("اطلاعات محصولات از کافه‌بازار دریافت نشد؛ دکمه‌های خرید غیرفعال هستند. ${error.message ?: ""}")
            }
        }
    }

    fun subscribe() = launchSubscriptionFlow(SUBSCRIPTION_SKU, false)

    fun subscribeTrial() = launchSubscriptionFlow(TRIAL_SUBSCRIPTION_SKU, true)

    private fun launchSubscriptionFlow(productId: String, isTrial: Boolean) {
        val productAvailable = if (isTrial) trialProductAvailable else annualProductAvailable
        if (!productAvailable) {
            onStatus(false, if (isTrial) "دوره آزمایشی در کافه‌بازار در دسترس نیست؛ فعلاً امکان خرید وجود ندارد."
                else "اشتراک ۳۶۵ روزه در کافه‌بازار در دسترس نیست؛ فعلاً امکان خرید وجود ندارد.")
            return
        }
        if (!connected) {
            onStatus(false, "ابتدا کافه‌بازار را نصب و وارد حساب خود کنید، سپس دوباره تلاش کنید.")
            connect()
            return
        }
        payment.subscribeProduct(
            registry = activity.activityResultRegistry,
            request = PurchaseRequest(
                productId = productId,
                payload = if (isTrial) "baby-monitor-camera-trial" else "baby-monitor-camera-subscription"
            )
        ) {
            purchaseFlowBegan {
                onStatus(false, if (isTrial) "صفحه فعال‌سازی دوره آزمایشی رایگان در کافه‌بازار باز شد؛ مراحل را تکمیل کنید."
                    else "صفحه خرید اشتراک یک‌ساله در کافه‌بازار باز شد؛ مراحل پرداخت را تکمیل کنید.")
            }
            failedToBeginFlow { error ->
                onStatus(false, "شروع فرایند اشتراک ناموفق بود: ${error.message ?: "خطای نامشخص"}")
                refresh()
            }
            purchaseSucceed {
                onStatus(false, "درخواست اشتراک ثبت شد؛ در حال بررسی وضعیت فعال‌بودن آن…")
                refresh()
            }
            purchaseCanceled {
                onStatus(false, "فرایند اشتراک لغو شد.")
                refresh()
            }
            purchaseFailed { error ->
                onStatus(false, "فعال‌سازی اشتراک ناموفق بود: ${error.message ?: "خطای نامشخص"}")
                refresh()
            }
        }
    }

    fun disconnect() {
        connected = false
        annualProductAvailable = false
        trialProductAvailable = false
        onProducts(false, false)
        connection?.disconnect()
        connection = null
    }

    companion object {
        const val SUBSCRIPTION_SKU = "Baby-monitor-camera"
        const val TRIAL_SUBSCRIPTION_SKU = "trial_subscription"
        private const val RSA_PUBLIC_KEY = "MIHNMA0GCSqGSIb3DQEBAQUAA4G7ADCBtwKBrwC2AVKAXbSFu1D2FCHxx8iTVKzxjM1UpeiBzN+9R4xEtLu8bW8MGcMVKD5Jo1BSxdwXjzvycv3GhUKnNxcn356sVAeYFb9BbnoWszHMEVtLCx4bEW0yWOm9qmrAI05kL/cCs84Q9bFPjRJiSoQ7kOiL/ukuj3Cs1scV5PSphbfU8mtxXCHJK5vHdoGUj6edzIKBH4PlRo4zmQR56Kb5vFUc7I8BSkL/QaK5KGMpIF8CAwEAAQ=="
    }
}
