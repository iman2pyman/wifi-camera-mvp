package com.iman2pyman.wificamera

import androidx.appcompat.app.AppCompatActivity
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest

/**
 * Cafe Bazaar subscription manager.
 * Trial eligibility and duration are always read from Bazaar; no local clock grants trial access.
 */
class BazaarSubscriptionManager(
    private val activity: AppCompatActivity,
    private val onStatus: (Boolean, String) -> Unit,
    private val onTrialStatus: (Boolean, String) -> Unit
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

    fun connect() {
        if (connection != null) return
        connection = payment.connect {
            connectionSucceed {
                connected = true
                refresh()
                checkTrialAvailability()
            }
            connectionFailed { error ->
                connected = false
                onStatus(false, "اتصال به پرداخت کافه‌بازار برقرار نشد: \${error.message ?: "خطای نامشخص"}")
                onTrialStatus(false, "برای بررسی دوره آزمایشی، اتصال به کافه‌بازار لازم است.")
            }
            disconnected {
                connected = false
                onStatus(false, "ارتباط پرداخت کافه‌بازار قطع شد؛ برای بررسی دوباره تلاش کنید.")
                onTrialStatus(false, "ارتباط با کافه‌بازار قطع شده است.")
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
                // This app currently has one subscription product. Bazaar may return
                // a trial entitlement as a separate subscription item.
                val active = subscriptions.isNotEmpty()
                onStatus(
                    active,
                    if (active) "اشتراک ویژه یا دوره آزمایشی شما فعال است."
                    else "اشتراک ویژه فعالی برای این حساب پیدا نشد."
                )
                if (!active) checkTrialAvailability()
            }
            queryFailed { error ->
                onStatus(false, "بررسی اشتراک ناموفق بود: \${error.message ?: "خطای نامشخص"}")
            }
        }
    }

    fun checkTrialAvailability() {
        if (!connected) {
            onTrialStatus(false, "برای بررسی دوره آزمایشی، ابتدا به کافه‌بازار متصل شوید.")
            return
        }
        payment.checkTrialSubscription {
            checkTrialSubscriptionSucceed { trialInfo ->
                val isSevenDayTrial = trialInfo.isAvailable && trialInfo.trialPeriodDays == TRIAL_DAYS
                onTrialStatus(
                    isSevenDayTrial,
                    when {
                        isSevenDayTrial -> "شما واجد شرایط دریافت ۷ روز اشتراک آزمایشی رایگان هستید."
                        !trialInfo.isAvailable -> "دوره آزمایشی رایگان قبلاً استفاده شده یا برای این حساب در دسترس نیست."
                        else -> "مدت دوره آزمایشی ثبت‌شده در کافه‌بازار \${trialInfo.trialPeriodDays} روز است؛ برای فعال‌سازی، باید دقیقاً ۷ روز تنظیم شود."
                    }
                )
            }
            checkTrialSubscriptionFailed { error ->
                onTrialStatus(
                    false,
                    "بررسی دوره آزمایشی ممکن نشد: \${error.message ?: "کافه‌بازار را به‌روزرسانی و دوباره تلاش کنید."}"
                )
            }
        }
    }

    fun startTrial() {
        if (!connected) {
            onTrialStatus(false, "ابتدا کافه‌بازار را نصب و وارد حساب خود کنید، سپس دوباره تلاش کنید.")
            connect()
            return
        }
        // Re-check eligibility immediately before opening Bazaar's subscription screen.
        payment.checkTrialSubscription {
            checkTrialSubscriptionSucceed { trialInfo ->
                if (!trialInfo.isAvailable || trialInfo.trialPeriodDays != TRIAL_DAYS) {
                    onTrialStatus(
                        false,
                        if (!trialInfo.isAvailable) {
                            "این حساب در حال حاضر واجد شرایط دوره آزمایشی رایگان نیست."
                        } else {
                            "مدت دوره آزمایشی کافه‌بازار ۷ روز نیست؛ امکان فعال‌سازی از داخل برنامه وجود ندارد."
                        }
                    )
                } else {
                    activity.runOnUiThread {
                        onTrialStatus(false, "در حال بازکردن صفحه فعال‌سازی دوره آزمایشی در کافه‌بازار…")
                        launchSubscriptionFlow()
                    }
                }
            }
            checkTrialSubscriptionFailed { error ->
                onTrialStatus(false, "بررسی شرایط دوره آزمایشی ناموفق بود: \${error.message ?: "خطای نامشخص"}")
            }
        }
    }

    fun subscribe() {
        if (!connected) {
            onStatus(false, "ابتدا کافه‌بازار را نصب و وارد حساب خود کنید، سپس دوباره تلاش کنید.")
            connect()
            return
        }
        launchSubscriptionFlow()
    }

    private fun launchSubscriptionFlow() {
        payment.subscribeProduct(
            registry = activity.activityResultRegistry,
            request = PurchaseRequest(
                productId = SUBSCRIPTION_SKU,
                payload = "baby-monitor-camera-subscription"
            )
        ) {
            purchaseFlowBegan {
                onStatus(false, "صفحه اشتراک کافه‌بازار باز شد؛ مراحل نمایش‌داده‌شده را تکمیل کنید.")
            }
            failedToBeginFlow { error ->
                onStatus(false, "شروع فرایند اشتراک ناموفق بود: \${error.message ?: "خطای نامشخص"}")
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
                onStatus(false, "فعال‌سازی اشتراک ناموفق بود: \${error.message ?: "خطای نامشخص"}")
                refresh()
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
        const val TRIAL_DAYS = 7
        private const val RSA_PUBLIC_KEY = "MIHNMA0GCSqGSIb3DQEBAQUAA4G7ADCBtwKBrwC2AVKAXbSFu1D2FCHxx8iTVKzxjM1UpeiBzN+9R4xEtLu8bW8MGcMVKD5Jo1BSxdwXjzvycv3GhUKnNxcn356sVAeYFb9BbnoWszHMEVtLCx4bEW0yWOm9qmrAI05kL/cCs84Q9bFPjRJiSoQ7kOiL/ukuj3Cs1scV5PSphbfU8mtxXCHJK5vHdoGUj6edzIKBH4PlRo4zmQR56Kb5vFUc7I8BSkL/QaK5KGMpIF8CAwEAAQ=="
    }
}
