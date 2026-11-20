package cat.tecnocampus.library.domain;

/** What must happen to the stock a purchase took, once its payment is decided. */
public enum StockAction {

    /** Paid: the copies are sold for good, and the receipt is issued. */
    SELL,

    /** Declined or failed: no money was taken, the copies go back on sale. */
    RELEASE,

    /** This attempt has lost its right to decide (another attempt took over): do nothing. */
    NONE
}
