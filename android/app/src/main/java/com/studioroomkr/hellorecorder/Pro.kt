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
import com.android.billingclient.api.PendingPurchasesParams
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
        // BillingClient 는 한 번만 만든다. App(프로세스 시작)·MainActivity·ProActivity 가
        // 각각 init 을 부를 수 있어, 이미 만들었으면 재구축하지 않고 구매만 다시 조회한다.
        if (billing == null) connect() else restore()
    }

    private fun connect() {
        if (billing?.isReady == true) {
            queryProduct(); restore(); return
        }
        billing = BillingClient.newBuilder(appCtx)
            // Billing 8: 무인자 enablePendingPurchases() 는 제거됨. 어떤 상품 유형에
            // 보류 결제를 허용할지 명시해야 한다. 우리 상품은 1회성(비소비) 하나뿐.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            // 서비스가 끊기면 라이브러리가 알아서 다시 붙는다(직접 재연결 로직 불필요).
            .enableAutoServiceReconnection()
            .setListener(purchasesUpdated)
            .build()
        billing?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProduct(); restore()
                }
            }
            override fun onBillingServiceDisconnected() { /* enableAutoServiceReconnection 이 처리 */ }
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
        // Billing 8: 콜백 2번째 인자가 List<ProductDetails> → QueryProductDetailsResult 로 바뀜.
        billing?.queryProductDetailsAsync(params) { _, result ->
            productDetails = result.productDetailsList.firstOrNull()
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
        ) { result, purchases ->
            // 응답이 OK 일 때만 소유 여부를 신뢰한다. 네트워크·Play 서비스 일시 오류로
            // 조회가 실패하면 빈 목록이 오는데, 예전엔 이를 '미보유'로 보고 setPro(false) 해
            // 정상 결제 사용자의 Pro 를 꺼 버렸다. 오류 시엔 캐시 상태를 그대로 둔다.
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
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
