package pt.tiago.zerodroid.service

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.zerodroid.data.SignInStatus

class SignInNotifyPolicyTest {
    private val statuses = listOf(SignInStatus.NotOidc, SignInStatus.Fresh, SignInStatus.Expiring(100), SignInStatus.Expired)

    @Test
    fun onlyPendingWorkInTheBackgroundNotifies() {
        for (s in statuses) for (holding in listOf(true, false)) for (fg in listOf(true, false)) {
            val expected = when {
                !holding || fg -> null
                s is SignInStatus.Expiring -> SignInNotice.Expiring
                s == SignInStatus.Expired -> SignInNotice.Expired
                else -> null
            }
            assertEquals("$s holding=$holding fg=$fg", expected, signInNotification(s, holding, fg))
        }
    }

    @Test
    fun expiredStaysPostedAfterAnExpiringNoticeEvenWhenWorkWasDropped() {
        assertEquals(SignInNotice.Expired, signInNotification(SignInStatus.Expired, holding = false, foreground = false, shown = SignInNotice.Expiring))
        assertEquals(null, signInNotification(SignInStatus.Expired, holding = false, foreground = false, shown = null))
        assertEquals(null, signInNotification(SignInStatus.Expired, holding = false, foreground = true, shown = SignInNotice.Expiring))
    }

    @Test
    fun noReminderWhileTheUserIsAlreadyRenewing() {
        assertEquals(null, signInNotification(SignInStatus.Expiring(100), holding = true, foreground = false, renewing = true))
        assertEquals(SignInNotice.Expired, signInNotification(SignInStatus.Expired, holding = true, foreground = false, renewing = true))
    }
}
