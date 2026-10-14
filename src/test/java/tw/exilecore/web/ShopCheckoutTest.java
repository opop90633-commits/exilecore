package tw.exilecore.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 贊助商店付款：PAYUNi 回傳的結果怎麼處理、HTML 跳脫。 */
class ShopCheckoutTest {

    @Test
    void decide() {
        assertEquals("paid", ShopCheckout.decide("SUCCESS", "1", 1000, 1000));
        assertEquals("mismatch", ShopCheckout.decide("SUCCESS", "1", 1, 1000));
        assertEquals("waiting", ShopCheckout.decide("SUCCESS", "0", 1000, 1000)); // ATM、超商代碼取號
        assertEquals("pending", ShopCheckout.decide("UNKNOWN", "", 0, 1000));
        assertEquals("pending", ShopCheckout.decide("SUCCESS", "8", 1000, 1000));
        assertEquals("failed", ShopCheckout.decide("SUCCESS", "2", 1000, 1000));
        assertEquals("failed", ShopCheckout.decide("SUCCESS", "3", 1000, 1000));
        assertEquals("failed", ShopCheckout.decide("UPP01001", "", 0, 1000));
        assertEquals("failed", ShopCheckout.decide("", "1", 1000, 1000)); // 沒寫成功就不發
    }

    @Test
    void escapes() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", ShopCheckout.esc("<a href=\"x\">&'"));
        assertEquals("", ShopCheckout.esc(null));
    }

    @Test
    void orderNumberFormat() {
        final String no = new ShopCheckout(null, () -> "").newOrderNo();
        assertTrue(no.matches("SR\\d{12}[A-Z2-9]{6}"), no);
        assertTrue(no.length() <= 25);
    }
}
