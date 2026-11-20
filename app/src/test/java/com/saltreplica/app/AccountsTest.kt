package com.saltreplica.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = App::class)
class AccountsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Accounts.init(context)
        Accounts.clear(Accounts.KUWO)
        Accounts.clear(Accounts.NETEASE)
        Accounts.clear(Accounts.KUGOU)
    }

    @Test
    fun providersDefaultToOn() {
        assertTrue(Accounts.providerEnabled(Accounts.KUWO))
        assertTrue(Accounts.providerEnabled(Accounts.NETEASE))
        assertTrue(Accounts.providerEnabled(Accounts.KUGOU))
    }

    @Test
    fun providerTogglePersists() {
        Accounts.toggleProvider(Accounts.KUWO)
        assertFalse(Accounts.providerEnabled(Accounts.KUWO))
        assertTrue(Accounts.providerEnabled(Accounts.NETEASE))
        Accounts.toggleProvider(Accounts.KUWO)
        assertTrue(Accounts.providerEnabled(Accounts.KUWO))
    }

    @Test
    fun kugouCookieOnlySentToKugouHost() {
        Accounts.save(Accounts.KUGOU, "kugouID=99; KuMing=test", "")
        assertTrue(Accounts.loggedIn(Accounts.KUGOU))
        assertFalse(Accounts.cookieForHost("m.kugou.com").isBlank())
        assertTrue(Accounts.cookieForHost("music.163.com").isBlank())
    }

    @Test
    fun loginStateFollowsCookie() {
        assertFalse(Accounts.loggedIn(Accounts.NETEASE))
        Accounts.save(Accounts.NETEASE, "MUSIC_U=abc; __csrf=def", "小明")
        assertTrue(Accounts.loggedIn(Accounts.NETEASE))
        assertTrue(Accounts.nickname(Accounts.NETEASE) == "小明")
        Accounts.clear(Accounts.NETEASE)
        assertFalse(Accounts.loggedIn(Accounts.NETEASE))
    }

    @Test
    fun kuwoTokenParsedFromCookie() {
        Accounts.save(Accounts.KUWO, "kw_token=tok123; userid=42", "")
        assertTrue(Accounts.loggedIn(Accounts.KUWO))
        assertTrue(Accounts.kuwoToken() == "tok123")
    }

    @Test
    fun cookieOnlySentForLoggedInHost() {
        assertTrue(Accounts.cookieForHost("music.163.com").isBlank())
        Accounts.save(Accounts.NETEASE, "MUSIC_U=abc", "")
        assertFalse(Accounts.cookieForHost("music.163.com").isBlank())
        assertTrue(Accounts.cookieForHost("search.kuwo.cn").isBlank())
    }
}
