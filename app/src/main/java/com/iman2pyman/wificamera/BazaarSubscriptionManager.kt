package com.iman2pyman.wificamera

import androidx.appcompat.app.AppCompatActivity
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.PaymentConfiguration
import ir.cafebazaar.poolakey.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest

/**
 * Cafe Bazaar 365-day premium subscription.
 * The RSA key is a public verification key, not the app signing private key.
 */
class BazaarSubscriptionManager(
    private val activity: AppCompatActivity,
    private val onStatus: (Boolean, String) -> Unit
) {
    private val payment = Payment(
        context = activity,
        config = PaymentConfiguration(
            localSecurityCheck = SecurityCheck.Enable(rsaPublicKey = RSA_PUBLIC_KEY),
            shouldSupportSubscription = true
        )
    )

    private var connection: ir.cafebazaar.poolakey.connection.Connection? = null
    @Volatile private var connected = false

    fun connect() {
        if (connection != null) return
        connection = payment.connect {
            connectionSucceed {
                connected = true
                refresh()
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
                val active = subscriptions.any { it.productId == SUBSCRIPTION_SKU }
                onStatus(
                    active,
                    if (active) "اشتراک ویژه شما فعال است."
                    else "اشتراک ویژه فعالی برای این حساب پیدا نشد."
                )
            }
            queryFailed { error ->
                onStatus(false, "بررسی اشتراک ناموفق بود: ${error.message ?: "خطای نامشخص"}")
            }
        }
    }

    fun subscribe() {
        if (!connected) {
            onStatus(false, "ابتدا کافه‌بازار را نصب و وارد حساب خود کنید، سپس دوباره تلاش کنید.")
            connect()
            return
        }
        payment.subscribeProduct(
            registry = activity.activityResultRegistry,
            request = PurchaseRequest(
                productId = SUBSCRIPTION_SKU,
                requestCode = SUBSCRIPTION_REQUEST_CODE,
                payload = "baby-monitor-camera-365-day"
            )
        ) {
            purchaseFlowBegan {
                onStatus(false, "صفحه پرداخت کافه‌بازار باز شد؛ خرید را تکمیل کنید.")
            }
            failedToBeginFlow { error ->
                onStatus(false, "شروع پرداخت ناموفق بود: ${error.message ?: "خطای نامشخص"}")
            }
            purchaseSucceed {
                onStatus(true, "خرید با موفقیت ثبت شد؛ در حال بررسی فعال‌بودن اشتراک…")
                refresh()
            }
            purchaseCanceled {
                onStatus(false, "خرید اشتراک لغو شد.")
            }
            purchaseFailed { error ->
                onStatus(false, "تأیید خرید ناموفق بود: ${error.message ?: "خطای نامشخص"}")
            }
        }
    }

    fun disconnect() {
        connected = false
        connection?.disconnect()
        connection = null
    }

    companion object {
        const val SUBSCRIPTION_SKU = "Baby-monitor-camera"
        private const val SUBSCRIPTION_REQUEST_CODE = 3652026
        private const val RSA_PUBLIC_KEY = "$key"
    }
}
