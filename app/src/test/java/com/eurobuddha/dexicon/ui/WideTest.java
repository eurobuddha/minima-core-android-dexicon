package com.eurobuddha.dexicon.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;


import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WideTest {
    @Test public void phoneStacksWideSplitsAndBackAgain() {
        RuntimeEnvironment.setQualifiers("w411dp-h800dp");
        Context c = RuntimeEnvironment.getApplication();
        LinearLayout host = new LinearLayout(c);
        View ticker = new View(c), funds = new View(c), book = new View(c), ticket = new View(c);
        List<View> cards = Arrays.asList(ticker, funds, book, ticket), left = Arrays.asList(ticker, book);

        Wide.arrange(host, cards, left);
        assertEquals("phone: one stack in phone order", 4, host.getChildCount());
        assertSame(funds, host.getChildAt(1));

        RuntimeEnvironment.setQualifiers("w933dp-h704dp");
        Wide.arrange(host, cards, left);
        assertEquals("wide: two columns", 2, host.getChildCount());
        LinearLayout l = (LinearLayout) host.getChildAt(0), r = (LinearLayout) host.getChildAt(1);
        assertSame(ticker, l.getChildAt(0)); assertSame(book, l.getChildAt(1));
        assertSame(funds, r.getChildAt(0)); assertSame(ticket, r.getChildAt(1));

        RuntimeEnvironment.setQualifiers("w411dp-h800dp");
        Wide.arrange(host, cards, Collections.emptyList());
        assertEquals("folded again: same views, one stack", 4, host.getChildCount());
        assertSame(ticket, host.getChildAt(3));
    }
}
