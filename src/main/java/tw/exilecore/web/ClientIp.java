package tw.exilecore.web;

/**
 * 對方的真實 IP（2026-10-25：從贊助商店的 ShopCheckout 搬出來；PAYUNi 拿掉後 /tree/calc 的次數限制還要用）。
 * 前面有反向代理（本機或內網連進來）時看代理給的標頭：Cloudflare 的 CF-Connecting-IP（proxy 寫 cloudflare 或空白才信）、
 * X-Forwarded-For 的最後一個、X-Real-IP。
 */
final class ClientIp {

    private ClientIp() {
    }

    static String of(final WebServer.Request request, final boolean trustProxy, final boolean cloudflare) {
        final String remote = request.remote() == null ? "" : request.remote();
        if (trustProxy && privateAddress(remote)) {
            final String cf = cloudflare ? request.header("cf-connecting-ip") : null;
            if (cf != null && !cf.isBlank()) {
                return cut(cf.trim());
            }
            final String forwarded = request.header("x-forwarded-for");
            if (forwarded != null && !forwarded.isBlank()) {
                final String[] parts = forwarded.split(",");
                return cut(parts[parts.length - 1].trim()); // 最後一個是最近一層代理看到的（前面的可以被偽造）
            }
            final String real = request.header("x-real-ip");
            if (real != null && !real.isBlank()) {
                return cut(real.trim());
            }
        }
        return remote;
    }

    static boolean privateAddress(final String ip) {
        return ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1") || ip.startsWith("10.") || ip.startsWith("192.168.")
                || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*") || ip.startsWith("fd") || ip.startsWith("fc");
    }

    private static String cut(final String text) {
        return text.length() <= 64 ? text : text.substring(0, 64);
    }
}
