package de.voidnode.trading4j.expertadvisorfactory;

import java.time.Instant;
import java.util.Optional;

import de.voidnode.trading4j.api.Broker;
import de.voidnode.trading4j.api.Either;
import de.voidnode.trading4j.api.Failed;
import de.voidnode.trading4j.api.Indicator;
import de.voidnode.trading4j.api.MarketDataListener;
import de.voidnode.trading4j.api.MoneyManagement;
import de.voidnode.trading4j.api.OrderEventListener;
import de.voidnode.trading4j.api.OrderManagement;
import de.voidnode.trading4j.api.UnrecoverableProgrammingError;
import de.voidnode.trading4j.api.UsedVolumeManagement;
import de.voidnode.trading4j.domain.ForexSymbol;
import de.voidnode.trading4j.domain.TimeFrame.M1;
import de.voidnode.trading4j.domain.Volume;
import de.voidnode.trading4j.domain.marketdata.MarketData;
import de.voidnode.trading4j.domain.monetary.Price;
import de.voidnode.trading4j.domain.orders.BasicPendingOrder;
import de.voidnode.trading4j.domain.orders.CloseConditions;
import de.voidnode.trading4j.domain.orders.MutablePendingOrder;
import de.voidnode.trading4j.domain.orders.PendingOrder;

/**
 * Adds the {@link Volume} to trade to a {@link BasicPendingOrder} from a trading strategy by requesting it from the
 * money management.
 * 
 * @author Raik Bieniek
 * @param <C>
 *            The market the improper-market-situation-{@link Indicator} expect as input.
 */
class StrategyMoneyManagement<C extends MarketData<M1>>
        implements Broker<BasicPendingOrder>, MarketDataListener<C> {

    private static final Failed NO_VOLUME = new Failed(
            "Could not place the pending order at the broker because the money management did not provide money.");

    private final Broker<PendingOrder> broker;
    private final MoneyManagement moneyManagement;
    private final ForexSymbol forexSymbol;
    private final Volume allowedStepSize;

    private Optional<Price> lastMarketData = Optional.empty();
    private Optional<Price> accountCurrencyExchangeRate = Optional.empty();

    private ForexSymbol accountCurrencyExchangeSymbol;

    /**
     * Initializes an instance with all its dependencies.
     * 
     * @param broker
     *            The {@link Broker} that should be used to trade concrete orders.
     * @param moneyManagement
     *            Used to request {@link Volume}s for trades.
     * @param forexSymbol
     *            The symbol that is traded.
     * @param accountCurrencyExchangeSymbol
     *            The symbol that converts the account currency to the quote currency of the traded symbol.
     * @param allowedStepSize
     *            The allowed step size for volumes.
     */
    StrategyMoneyManagement(final Broker<PendingOrder> broker, final MoneyManagement moneyManagement,
            final ForexSymbol forexSymbol, final ForexSymbol accountCurrencyExchangeSymbol,
            final Volume allowedStepSize) {
        this.broker = broker;
        this.moneyManagement = moneyManagement;
        this.forexSymbol = forexSymbol;
        this.accountCurrencyExchangeSymbol = accountCurrencyExchangeSymbol;
        this.allowedStepSize = allowedStepSize;
    }

    @Override
    public Either<Failed, OrderManagement> sendOrder(final BasicPendingOrder order,
            final OrderEventListener eventListener) {
        final Price lastPrice = lastMarketData.orElseThrow(() -> new UnrecoverableProgrammingError(
                "An order was send before the current value of the traded symbol was passed to this instance."));
        final Price lastExchangeRate = accountCurrencyExchangeRate.orElseThrow(() -> new UnrecoverableProgrammingError(
                "An order was send before the current value of the symbol exchanging account currency "
                        + " to the quote currency of the traded symbol" + " was passed to this class."));
        final Price difference = new Price(
                Math.abs(order.getEntryPrice().asPipette() - order.getCloseConditions().getStopLoose().asPipette()));
        final Optional<UsedVolumeManagement> volumeManagement = moneyManagement.requestVolume(forexSymbol, lastPrice,
                accountCurrencyExchangeSymbol, lastExchangeRate, difference, allowedStepSize);

        if (volumeManagement.isPresent()) {
            final VolumeReturner returner = new VolumeReturner(volumeManagement.get(), eventListener);
            final Either<Failed, OrderManagement> result = broker
                    .sendOrder(new MutablePendingOrder(order).setVolume(volumeManagement.get().getVolume())
                            .toImmutablePendingOrder(), returner)
                    // register the original order management at the volume returner
                    .mapRight(origMgmnt -> {
                        returner.setOrderManagement(origMgmnt);
                        return returner;
                    });
            if (result.hasLeft()) {
                // return volume when sending order has failed.
                volumeManagement.get().releaseVolume();
            }
            return result;
        } else {
            return Either.withLeft(NO_VOLUME);
        }
    }

    @Override
    public void newData(final C marketData) {
        this.lastMarketData = Optional.of(marketData.getClose());
    }

    /**
     * Updates the current price of the symbol for exchanging the account currency to the quote currency of the traded
     * symbol.
     * 
     * @param newPrice
     *            The updated account currency exchange rate.
     */
    public void updateAccountCurrencyExchangeRateChanged(final Price newPrice) {
        this.accountCurrencyExchangeRate = Optional.of(newPrice);
    }

    /**
     * Returns {@link Volume} to the money management when the order is closed.
     *
     */
    private static final class VolumeReturner implements OrderManagement, OrderEventListener {

        private final OrderEventListener origEventListener;
        private final UsedVolumeManagement volumeManagement;
        private OrderManagement origOrderManagement;

        VolumeReturner(final UsedVolumeManagement volumeManagement, final OrderEventListener origEventListener) {
            this.volumeManagement = volumeManagement;
            this.origEventListener = origEventListener;
        }

        public void setOrderManagement(final OrderManagement origOrderManagement) {
            this.origOrderManagement = origOrderManagement;
        }

        @Override
        public void orderOpened(final Instant time, final Price price) {
            origEventListener.orderOpened(time, price);

        }

        @Override
        public void orderClosed(final Instant time, final Price price) {
            volumeManagement.releaseVolume();
            origEventListener.orderClosed(time, price);
        }

        @Override
        public void closeOrCancelOrder() {
            origOrderManagement.closeOrCancelOrder();
            volumeManagement.releaseVolume();

        }

        @Override
        public Optional<Failed> changeCloseConditionsOfOrder(final CloseConditions conditions) {
            return origOrderManagement.changeCloseConditionsOfOrder(conditions);
        }
    }
}
