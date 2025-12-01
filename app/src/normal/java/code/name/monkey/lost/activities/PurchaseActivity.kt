package code.name.monkey.lost.activities

import android.view.MenuItem
import code.name.monkey.lost.activities.base.AbsThemeActivity
import com.anjlab.android.iab.v3.BillingProcessor
import com.anjlab.android.iab.v3.PurchaseInfo

class PurchaseActivity : AbsThemeActivity(), BillingProcessor.IBillingHandler {

    private lateinit var billingProcessor: BillingProcessor


    override fun onProductPurchased(productId: String, details: PurchaseInfo?) {
        setResult(RESULT_OK)
    }

    override fun onPurchaseHistoryRestored() {

    }

    override fun onBillingError(errorCode: Int, error: Throwable?) {
    }

    override fun onBillingInitialized() {
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return true
    }

    override fun onDestroy() {
        billingProcessor.release()
        super.onDestroy()
    }
}