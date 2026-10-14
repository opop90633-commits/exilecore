package tw.exilecore.web;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 統一金流的加解密：照官方文件 Java 範例的金鑰、明文與結果比對。 */
class PayUniCryptoTest {

    private static final String KEY = "12345678901234567890123456789012";
    private static final String IV = "1234567890123456";
    private static final String PLAIN = "MerID=ABC&MerTradeNo=1658198662_93966&TradeAmt=7017&Timestamp=1658198662"
            + "&ProdDesc=%E5%95%86%E5%93%81%E8%AA%AA%E6%98%8E&UsrMail=a%40presco.ws"
            + "&ReturnURL=http%3A%2F%2Flapi-epay.presco.com.tw%2Fapi%2Fupp%2Freturn";
    private static final String ENCRYPTED = "47396636346f66735853653367396942344f587a3775696b34752b593765564a6e337365625a6176316a72706d7377536938436a41695239773545764f3251784b6257665273715374476b70385232564a4643306d655151764855616c7a7a45764c4b4e5462654c574a536553346d527572413357794379324f59555466494a5977344b6f50432f72733564723853546a516d44396c4744672b5a7132696967337345664e4b6f625759637579737a47715767706d6e76786f3773693139534165485374612b673343594a4e65744a4d6b396b6f6b304c6b716d2f596e64494e4863456f35655a693833494f346b4f307679733346695a48734751454b386a4453494c613955556661774234697770506752306e70673d3d3a3a3a50724e743974526e6332704775547a7a7362494a33413d3d";
    private static final String HASH = "5CED70BDE1027F5DB2512C6B0957D698DADA0DBB67F3051C19A0F48C7455E249";

    @Test
    void matchesOfficialExample() {
        final PayUniCrypto crypto = new PayUniCrypto(KEY, IV);
        assertEquals(ENCRYPTED, crypto.encryptText(PLAIN));
        assertEquals(HASH, crypto.hash(ENCRYPTED));
        assertEquals(PLAIN, crypto.decryptText(ENCRYPTED));
        assertTrue(crypto.verify(ENCRYPTED, HASH));
        assertTrue(crypto.verify(ENCRYPTED, HASH.toLowerCase()));
        assertFalse(crypto.verify(ENCRYPTED, HASH.replace('5', '6')));
    }

    @Test
    void queryLikePhp() {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("MerID", "ABC");
        params.put("MerTradeNo", "1658198662_93966");
        params.put("TradeAmt", "7017");
        params.put("Timestamp", "1658198662");
        params.put("ProdDesc", "商品說明");
        params.put("UsrMail", "a@presco.ws");
        params.put("ReturnURL", "http://lapi-epay.presco.com.tw/api/upp/return");
        assertEquals(PLAIN, PayUniCrypto.query(params));
        final PayUniCrypto crypto = new PayUniCrypto(KEY, IV);
        assertEquals(ENCRYPTED, crypto.encrypt(params));
        assertEquals(params, crypto.decrypt(ENCRYPTED));
        assertEquals("a+b%2A", PayUniCrypto.query(Map.of("x", "a b*")).substring(2));
    }

    @Test
    void phpShortExample() {
        final PayUniCrypto crypto = new PayUniCrypto(KEY, IV);
        assertEquals("47396636346f66735853533167396942344f587a3775696b34752b596e70452b3a3a3a4373354a5a5143306b7153467531354c6e6f554a69773d3d",
                crypto.encryptText("MerID=AAA&MerTradeNo=BBB"));
    }

    @Test
    void rejectsTampered() {
        final PayUniCrypto crypto = new PayUniCrypto(KEY, IV);
        final String bad = ENCRYPTED.substring(0, 10) + (ENCRYPTED.charAt(10) == '6' ? '7' : '6') + ENCRYPTED.substring(11);
        assertThrows(IllegalArgumentException.class, () -> crypto.decryptText(bad));
        assertThrows(IllegalArgumentException.class, () -> new PayUniCrypto("short", IV));
    }
}
