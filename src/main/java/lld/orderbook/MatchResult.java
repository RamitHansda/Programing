package lld.orderbook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of matching one incoming order: list of trades and book events.
 */
public final class MatchResult {

  private final List<Trade> trades = new ArrayList<>();
  private final List<BookEvent> bookEvents = new ArrayList<>();

  public List<Trade> getTrades() {
    return Collections.unmodifiableList(trades);
  }

  public List<BookEvent> getBookEvents() {
    return Collections.unmodifiableList(bookEvents);
  }

  void addTrade(Trade trade) {
    trades.add(trade);
  }

  void addBookEvent(BookEvent event) {
    bookEvents.add(event);
  }
}
