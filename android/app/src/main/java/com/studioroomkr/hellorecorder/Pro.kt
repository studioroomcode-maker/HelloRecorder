package com.studioroomkr.hellorecorder

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Pro 잠금 해제 (Google Play Billing, 1회 결제 비소비성 상품).
 *
 *  - Play Console > 수익 창출 > 인앱 상품에 상품ID `pro_unlock` 을 먼저 만들어야 함.
 *  - 앱 시작 시 init(ctx) → 보유 구매를 조회해 Pro 상태 복원.
 *  - 구매 화면(ProActivity)에서 purchase(activity) 호출.
 *  - Pro 상태는 Prefs 에 캐시되며, 변하면 onChanged 콜백.
 */
object Pro {

    const val PRODUCT_ID = "pro_unlock"

    @Volatile
    var isPro = false
        private set

    /** Pro 상태가 바뀌면 호출(메인 스레드). UI 갱신용. */
    var onChanged: (() -> Unit)? = null

    private lateinit var appCtx: Context
    private var billing: BillingClient? = null
    private var productDetails: ProductDetails? = null

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (p in purchases) handlePurchase(p)
        }
    }

    // 디버그 빌드(개발/내 기기)에서는 결제 없이 항상 Pro. 릴리스 빌드는 정상 결제.
    private val forcePro: Boolean get() = BuildConfig.DEBUG

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        isPro = Prefs.isPro(appCtx)   // 캐시값 먼저 반영
        if (forcePro) setPro(true)
        connect()
    }

    private fun connect() {
        if (billing?.isReady == true) {
            queryProduct(); restore(); return
        }
        @Suppress("DEPRECATION")
        billing = BillingClient.newBuilder(appCtx)
            .enablePendingPurchases()
            .setListener(purchasesUpdated)
            .build()
        billing?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct(); restore()
                }
            }
            override fun onBillingServiceDisconnected() { /* 다음 호출 시 재연결 */ }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()
        billing?.queryProductDetailsAsync(params) { _, list ->
            productDetails = list.firstOrNull()
        }
    }

    /** 표시용 가격(예: "₩3,900"). 상품 로딩 전이면 빈 문자열. */
    fun priceText(): String =
        productDetails?.oneTimePurchaseOfferDetails?.formattedPrice ?: ""

    fun isReady(): Boolean = productDetails != null

    /** 구매 흐름 시작. 상품이 아직 로딩 안 됐으면 false. */
    fun purchase(activity: Activity): Boolean {
        val pd = productDetails ?: return false
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd)
                        .build()
                )
            ).build()
        billing?.launchBillingFlow(activity, flowParams)
        return true
    }

    /** 보유 구매 복원(다른 기기/재설치 후). */
    fun restore() {
        billing?.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build()
        ) { _, purchases ->
            var owned = false
            for (p in purchases) {
                handlePurchase(p)
                if (p.products.contains(PRODUCT_ID) &&
                    p.purchaseState == Purchase.PurchaseState.PURCHASED
                ) owned = true
            }
            setPro(owned)
        }
    }

    private fun handlePurchase(p: Purchase) {
        if (p.purchaseState == Purchase.PurchaseState.PURCHASED &&
            p.products.contains(PRODUCT_ID)
        ) {
            setPro(true)
            if (!p.isAcknowledged) {
                billing?.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(p.purchaseToken).build()
                ) { /* ack 결과 무시 */ }
            }
        }
    }

    private fun setPro(value: Boolean) {
        // 디버그 빌드에선 복원/조회 결과와 무관하게 Pro 유지 (false 로 못 내려감)
        val v = value || forcePro
        if (isPro == v) return
        isPro = v
        Prefs.setPro(appCtx, v)
        Handler(Looper.getMainLooper()).post { onChanged?.invoke() }
    }
}
