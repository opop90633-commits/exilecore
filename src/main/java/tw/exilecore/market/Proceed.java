package tw.exilecore.market;

import java.util.UUID;

/** 一筆等著領的收入（市集賣掉東西、通貨交易所成交）。只在主執行緒用；資料庫是 market_proceeds。 */
final class Proceed {

    final String id;
    final UUID account;
    final String currencyId;
    final int amount;
    /** market 或 exchange。 */
    final String source;
    final String note;
    final long createdAt;
    final String opId;
    /** 建立它的操作寫進資料庫了才能領。 */
    boolean confirmed;

    Proceed(final String id, final UUID account, final String currencyId, final int amount, final String source, final String note,
            final long createdAt, final String opId, final boolean confirmed) {
        this.id = id;
        this.account = account;
        this.currencyId = currencyId;
        this.amount = amount;
        this.source = source;
        this.note = note;
        this.createdAt = createdAt;
        this.opId = opId;
        this.confirmed = confirmed;
    }
}
