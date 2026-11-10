package com.eurobuddha.account;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Round-3 review (10 Nov): pools carried to a phone that never held their account (a device transfer copies the
 * recipes, not the account) were restore-only, but ANY phrase was accepted — the wrong account opened and could not sign
 * them. A restore must now be the account recorded on those pools (or this phone's own, when it had one).
 */
public class AccountSetupOwnersTest {
    private static Set<String> set(String... s) { return new HashSet<>(java.util.Arrays.asList(s)); }

    @Test public void onlyThePoolsAccountIsAccepted() {
        Set<String> none = Collections.emptySet();
        assertNull(AccountSetup.wrongAccount(none, set("C3BA14CD8AF95045"), "c3ba14cd8af95045"));
        assertNotNull(AccountSetup.wrongAccount(none, set("C3BA14CD8AF95045"), "0000000000000001"));
    }

    @Test public void thisPhonesOwnAccountStillRestores() {
        assertNull(AccountSetup.wrongAccount(set("aaaaaaaaaaaaaaaa"), Collections.emptySet(), "AAAAAAAAAAAAAAAA"));
        assertNotNull(AccountSetup.wrongAccount(set("aaaaaaaaaaaaaaaa"), Collections.emptySet(), "bbbbbbbbbbbbbbbb"));
        assertNull("either the phone's or the pools' account", AccountSetup.wrongAccount(set("aaaaaaaaaaaaaaaa"), set("bbbbbbbbbbbbbbbb"), "bbbbbbbbbbbbbbbb"));
    }

    @Test public void nothingRecordedAcceptsAsBefore() {
        assertNull(AccountSetup.wrongAccount(Collections.emptySet(), Collections.emptySet(), "0123456789abcdef"));
    }
}
