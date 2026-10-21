package com.crowja.damiupos.checkout;

import com.crowja.damiupos.model.Transaction;

/**
 * Ongkir untuk galon GIFT produk (owner 21/10/2026): galon gift memang diantar, jadi ongkir PER GALON ikut ditagih untuk galon itu —
 * cermin App\Support\Gifts::applyProductGift mode 'append' di web dan hitungan edit (tarif × jumlah galon), supaya total di HP, web,
 * agen, dan setelah diedit selalu sama. Ongkir borongan / tanpa ongkir tidak berubah karena gift.
 */
public final class GiftOngkir {
    private GiftOngkir() {}

    /** Ongkir galon gift: tarif × qty gift bila ongkirnya Per Galon dan tarif &gt; 0, selain itu 0. */
    public static double of(String ongkirType, double rate, int giftQty) {
        if (!Transaction.ONGKIR_PER_GALON.equals(ongkirType) || rate <= 0 || giftQty <= 0) return 0;
        return rate * giftQty;
    }
}
