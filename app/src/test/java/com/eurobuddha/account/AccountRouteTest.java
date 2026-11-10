package com.eurobuddha.account;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Each app's node layer routes by {@link Account#route}: with no account, wallet verbs are refused, chain reads pass. */
@RunWith(RobolectricTestRunner.class)
public class AccountRouteTest {
    static final Transport NODE = (c, g, t, cb) -> { };

    @Test public void withNoAccountWalletVerbsAreRefusedAndChainReadsPass() {
        Account.configure(new Account.Config("Test", null, null, null, null));
        Context c = RuntimeEnvironment.getApplication();
        assertEquals(Account.Route.NO_ACCOUNT, Account.route(c, NODE, "balance"));
        assertEquals(Account.Route.NO_ACCOUNT, Account.route(c, NODE, "send address:MxG081Z479YMTA95KCJBUW1B3JN0AMTBFVKG1HENPMM9C0GH76HUW4YYQWTRZFH amount:1"));
        assertEquals(Account.Route.NO_ACCOUNT, Account.route(c, NODE, "txncreate id:x"));
        assertEquals(Account.Route.NODE, Account.route(c, NODE, "coins address:MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G"));
        assertEquals(Account.Route.NODE, Account.route(c, NODE, "block"));
        assertEquals(Account.Route.NODE, Account.route(c, NODE, "txpow onchain:0x" + "AB".repeat(32)));
        assertEquals(Account.Route.NODE, Account.route(c, NODE, "newscript trackall:true script:\"RETURN TRUE\""));
    }
}
