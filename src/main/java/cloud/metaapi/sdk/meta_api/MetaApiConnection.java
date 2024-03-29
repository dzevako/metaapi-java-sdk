package cloud.metaapi.sdk.meta_api;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.log4j.Logger;

import cloud.metaapi.sdk.clients.TimeoutException;
import cloud.metaapi.sdk.clients.meta_api.MetaApiWebsocketClient;
import cloud.metaapi.sdk.clients.meta_api.ReconnectListener;
import cloud.metaapi.sdk.clients.meta_api.SynchronizationListener;
import cloud.metaapi.sdk.clients.meta_api.TradeException;
import cloud.metaapi.sdk.clients.meta_api.models.MarketTradeOptions;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderAccountInformation;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderDeals;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderHistoryOrders;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderOrder;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderPosition;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderSymbolPrice;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderSymbolSpecification;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderTrade;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderTradeResponse;
import cloud.metaapi.sdk.clients.meta_api.models.PendingTradeOptions;
import cloud.metaapi.sdk.clients.meta_api.models.SynchronizationOptions;
import cloud.metaapi.sdk.clients.meta_api.models.MetatraderTrade.ActionType;
import cloud.metaapi.sdk.clients.models.*;

/**
 * Exposes MetaApi MetaTrader API connection to consumers
 */
public class MetaApiConnection extends SynchronizationListener implements ReconnectListener {

    private static Logger logger = Logger.getLogger(MetaApiConnection.class);
    private MetaApiWebsocketClient websocketClient;
    private MetatraderAccount account;
    private HashSet<String> ordersSynchronized = new HashSet<>();
    private HashSet<String> dealsSynchronized = new HashSet<>();
    private ConnectionRegistry connectionRegistry;
    private IsoTime historyStartTime = null;
    private String lastSynchronizationId = null;
    private String lastDisconnectedSynchronizationId = null;
    private TerminalState terminalState;
    private HistoryStorage historyStorage;
    private ConnectionHealthMonitor healthMonitor;
    private HashSet<String> subscriptions = new HashSet<>();
    private String shouldSynchronize;
    private int synchronizationRetryIntervalInSeconds;
    private boolean isSynchronized = false;
    private boolean closed = false;
    
    /**
     * Constructs MetaApi MetaTrader Api connection
     * @param websocketClient MetaApi websocket client
     * @param account MetaTrader account to connect to
     * @param historyStorage terminal history storage or {@code null}. 
     * By default an instance of MemoryHistoryStorage will be used.
     * @param connectionRegistry metatrader account connection registry
     */
    public MetaApiConnection(
        MetaApiWebsocketClient websocketClient,
        MetatraderAccount account,
        HistoryStorage historyStorage,
        ConnectionRegistry connectionRegistry
    ) {
        this(websocketClient, account, historyStorage, connectionRegistry, null);
    }
    
    /**
     * Constructs MetaApi MetaTrader Api connection
     * @param websocketClient MetaApi websocket client
     * @param account MetaTrader account to connect to
     * @param historyStorage terminal history storage or {@code null}. 
     * By default an instance of MemoryHistoryStorage will be used.
     * @param connectionRegistry metatrader account connection registry
     * @param historyStartTime history start sync time, or {@code null}
     */
    public MetaApiConnection(
        MetaApiWebsocketClient websocketClient,
        MetatraderAccount account,
        HistoryStorage historyStorage,
        ConnectionRegistry connectionRegistry,
        IsoTime historyStartTime
    ) {
        this.websocketClient = websocketClient;
        this.account = account;
        this.connectionRegistry = connectionRegistry;
        this.historyStartTime = historyStartTime;
        this.terminalState = new TerminalState();
        this.historyStorage = historyStorage != null 
            ? historyStorage : new MemoryHistoryStorage(account.getId(), connectionRegistry.getApplication());
        this.healthMonitor = new ConnectionHealthMonitor(this);
        websocketClient.addSynchronizationListener(account.getId(), this);
        websocketClient.addSynchronizationListener(account.getId(), this.terminalState);
        websocketClient.addSynchronizationListener(account.getId(), this.historyStorage);
        websocketClient.addSynchronizationListener(account.getId(), this.healthMonitor);
        websocketClient.addReconnectListener(this);
    }
    
    /**
     * Returns account information (see
     * https://metaapi.cloud/docs/client/websocket/api/readTradingTerminalState/readAccountInformation/).
     * @return completable future resolving with account information
     */
    public CompletableFuture<MetatraderAccountInformation> getAccountInformation() {
        return websocketClient.getAccountInformation(account.getId());
    }
    
    /**
     * Returns positions (see
     * https://metaapi.cloud/docs/client/websocket/api/readTradingTerminalState/readPositions/).
     * @return completable future resolving with array of open positions
     */
    public CompletableFuture<List<MetatraderPosition>> getPositions() {
        return websocketClient.getPositions(account.getId());
    }
    
    /**
     * Returns specific position (see
     * https://metaapi.cloud/docs/client/websocket/api/readTradingTerminalState/readPosition/).
     * @param positionId position id
     * @return completable future resolving with MetaTrader position found
     */
    public CompletableFuture<MetatraderPosition> getPosition(String positionId) {
        return websocketClient.getPosition(account.getId(), positionId);
    }
    
    /**
     * Returns open orders (see
     * https://metaapi.cloud/docs/client/websocket/api/readTradingTerminalState/readOrders/).
     * @return completable future resolving with open MetaTrader orders
     */
    public CompletableFuture<List<MetatraderOrder>> getOrders() {
        return websocketClient.getOrders(account.getId());
    }
    
    /**
     * Returns specific open order (see
     * https://metaapi.cloud/docs/client/websocket/api/readTradingTerminalState/readOrder/).
     * @param orderId order id (ticket number)
     * @return completable future resolving with metatrader order found
     */
    public CompletableFuture<MetatraderOrder> getOrder(String orderId) {
        return websocketClient.getOrder(account.getId(), orderId);
    }
    
    /**
     * Returns the history of completed orders for a specific ticket number (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveHistoricalData/readHistoryOrdersByTicket/).
     * @param ticket ticket number (order id)
     * @return completable future resolving with request results containing history orders found
     */
    public CompletableFuture<MetatraderHistoryOrders> getHistoryOrdersByTicket(String ticket) {
        return websocketClient.getHistoryOrdersByTicket(account.getId(), ticket);
    }
    
    /**
     * Returns the history of completed orders for a specific position id (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveHistoricalData/readHistoryOrdersByPosition/)
     * @param positionId position id
     * @return completable future resolving with request results containing history orders found
     */
    public CompletableFuture<MetatraderHistoryOrders> getHistoryOrdersByPosition(String positionId) {
        return websocketClient.getHistoryOrdersByPosition(account.getId(), positionId);
    }
    
    /**
     * Returns the history of completed orders for a specific time range (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveHistoricalData/readHistoryOrdersByTimeRange/)
     * @param startTime start of time range, inclusive
     * @param endTime end of time range, exclusive
     * @param offset pagination offset
     * @param limit pagination limit
     * @return completable future resolving with request results containing history orders found
     */
    public CompletableFuture<MetatraderHistoryOrders> getHistoryOrdersByTimeRange(
        IsoTime startTime, IsoTime endTime, int offset, int limit
    ) {
        return websocketClient.getHistoryOrdersByTimeRange(account.getId(), startTime, endTime, offset, limit);
    }
    
    /**
     * Returns history deals with a specific ticket number (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveHistoricalData/readDealsByTicket/).
     * @param ticket ticket number (deal id for MT5 or order id for MT4)
     * @return completable future resolving with request results containing deals found
     */
    public CompletableFuture<MetatraderDeals> getDealsByTicket(String ticket) {
        return websocketClient.getDealsByTicket(account.getId(), ticket);
    }
    
    /**
     * Returns history deals for a specific position id (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveHistoricalData/readDealsByPosition/).
     * @param positionId position id
     * @return completable future resolving with request results containing deals found
     */
    public CompletableFuture<MetatraderDeals> getDealsByPosition(String positionId) {
        return websocketClient.getDealsByPosition(account.getId(), positionId);
    }
    
    /**
     * Returns history deals with for a specific time range (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveHistoricalData/readDealsByTimeRange/).
     * @param startTime start of time range, inclusive
     * @param endTime end of time range, exclusive
     * @param offset pagination offset
     * @param limit pagination limit
     * @return completable future resolving with request results containing deals found
     */
    public CompletableFuture<MetatraderDeals> getDealsByTimeRange(
        IsoTime startTime, IsoTime endTime, int offset, int limit
    ) {
        return websocketClient.getDealsByTimeRange(account.getId(), startTime, endTime, offset, limit);
    }
    
    /**
     * Clears the order and transaction history of a specified application so that it can be synchronized from scratch 
     * (see https://metaapi.cloud/docs/client/websocket/api/removeHistory/).
     * @return completable future resolving when the history is cleared
     */
    public CompletableFuture<Void> removeHistory() {
        return removeHistory(null);
    }
    
    /**
     * Clears the order and transaction history of a specified application so that it can be synchronized from scratch 
     * (see https://metaapi.cloud/docs/client/websocket/api/removeHistory/).
     * @param application application to remove history for, or {@code null}
     * @return completable future resolving when the history is cleared
     */
    public CompletableFuture<Void> removeHistory(String application) {
        historyStorage.reset();
        return websocketClient.removeHistory(account.getId(), application);
    }
    
    /**
     * Clears the order and transaction history of a specified application and removes application (see
     * https://metaapi.cloud/docs/client/websocket/api/removeApplication/).
     * @return completable future resolving when the history is cleared and application is removed
     */
    public CompletableFuture<Void> removeApplication() {
        historyStorage.reset();
        return websocketClient.removeApplication(account.getId());
    }
    
    /**
     * Creates a market buy order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createMarketBuyOrder(
        String symbol, double volume, Double stopLoss, Double takeProfit, MarketTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_BUY;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Creates a market sell order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createMarketSellOrder(
        String symbol, double volume, Double stopLoss, Double takeProfit, MarketTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_SELL;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Creates a limit buy order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param openPrice order limit price
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createLimitBuyOrder(
        String symbol, double volume, double openPrice,
        Double stopLoss, Double takeProfit, PendingTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_BUY_LIMIT;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.openPrice = openPrice;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Creates a limit sell order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param openPrice order limit price
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createLimitSellOrder(
        String symbol, double volume, double openPrice,
        Double stopLoss, Double takeProfit, PendingTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_SELL_LIMIT;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.openPrice = openPrice;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Creates a stop buy order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param openPrice order stop price
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createStopBuyOrder(
        String symbol, double volume, double openPrice,
        Double stopLoss, Double takeProfit, PendingTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_BUY_STOP;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.openPrice = openPrice;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Creates a stop sell order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param openPrice order stop price
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally
     * with {@link TradeException}, check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createStopSellOrder(
        String symbol, double volume, double openPrice,
        Double stopLoss, Double takeProfit, PendingTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_SELL_STOP;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.openPrice = openPrice;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Creates a stop limit buy order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param openPrice order stop price
     * @param stopLimitPrice the limit order price for the stop limit order
     * @param stopLoss stop loss price, or {@code null}
     * @param takeProfit take profit price, or {@code null}
     * @param options trade options, or {@code null}
     * @return completable future resolving with trade result or completing exceptionally
     * with {@link TradeException}, check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createStopLimitBuyOrder(
        String symbol, double volume, double openPrice, double stopLimitPrice,
        Double stopLoss, Double takeProfit, PendingTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_BUY_STOP_LIMIT;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.openPrice = openPrice;
        trade.stopLimitPrice = stopLimitPrice;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Creates a stop limit sell order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param symbol symbol to trade
     * @param volume order volume
     * @param openPrice order stop price
     * @param stopLimitPrice the limit order price for the stop limit order
     * @param stopLoss stop loss price, or {@code null}
     * @param takeProfit take profit price, or {@code null}
     * @param options trade options, or {@code null}
     * @return completable future resolving with trade result or completing exceptionally
     * with {@link TradeException}, check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> createStopLimitSellOrder(
        String symbol, double volume, double openPrice, double stopLimitPrice,
        Double stopLoss, Double takeProfit, PendingTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_TYPE_SELL_STOP_LIMIT;
        trade.symbol = symbol;
        trade.volume = volume;
        trade.openPrice = openPrice;
        trade.stopLimitPrice = stopLimitPrice;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Modifies a position (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param positionId position id to modify
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> modifyPosition(
        String positionId, Double stopLoss, Double takeProfit
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.POSITION_MODIFY;
        trade.positionId = positionId;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Partially closes a position (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param positionId position id to modify
     * @param volume volume to close
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> closePositionPartially(
        String positionId, double volume, MarketTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.POSITION_PARTIAL;
        trade.positionId = positionId;
        trade.volume = volume;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Fully closes a position (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param positionId position id to modify
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> closePosition(
        String positionId, MarketTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.POSITION_CLOSE_ID;
        trade.positionId = positionId;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Fully closes a position (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param positionId position id to close by opposite position
     * @param oppositePositionId opposite position id to close
     * @param options optional trade options, or {@code null}
     * @return completable future resolving with trade result or completing exceptionally
     * with {@link TradeException}, check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> closeBy(
        String positionId, String oppositePositionId, MarketTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.POSITION_CLOSE_BY;
        trade.positionId = positionId;
        trade.closeByPositionId = oppositePositionId;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Closes position by a symbol (see https://metaapi.cloud/docs/client/websocket/api/trade/)
     * @param symbol symbol to trade
     * @param options optional trade options or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> closePositionsBySymbol(
        String symbol, MarketTradeOptions options
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.POSITIONS_CLOSE_SYMBOL;
        trade.symbol = symbol;
        if (options != null) copyModelProperties(options, trade);
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Modifies a pending order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param orderId order id (ticket number)
     * @param openPrice order stop price
     * @param stopLoss optional stop loss price or {@code null}
     * @param takeProfit optional take profit price or {@code null}
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> modifyOrder(
        String orderId, double openPrice, double stopLoss, double takeProfit
    ) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_MODIFY;
        trade.orderId = orderId;
        trade.openPrice = openPrice;
        trade.stopLoss = stopLoss;
        trade.takeProfit = takeProfit;
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Cancels order (see https://metaapi.cloud/docs/client/websocket/api/trade/).
     * @param orderId order id (ticket number)
     * @return completable future resolving with trade result or completing exceptionally with {@link TradeException},
     * check error properties for error code details
     */
    public CompletableFuture<MetatraderTradeResponse> cancelOrder(String orderId) {
        MetatraderTrade trade = new MetatraderTrade();
        trade.actionType = ActionType.ORDER_CANCEL;
        trade.orderId = orderId;
        return websocketClient.trade(account.getId(), trade);
    }
    
    /**
     * Reconnects to the Metatrader terminal (see https://metaapi.cloud/docs/client/websocket/api/reconnect/).
     * @return completable future which resolves when reconnection started
     */
    public CompletableFuture<Void> reconnect() {
        return websocketClient.reconnect(account.getId());
    }
    
    /**
     * Requests the terminal to start synchronization process
     * (see https://metaapi.cloud/docs/client/websocket/synchronizing/synchronize/)
     * @return completable future which resolves when synchronization started
     */
    public CompletableFuture<Void> synchronize() {
        return CompletableFuture.runAsync(() -> {
            IsoTime lastHistoryOrderTime = historyStorage.getLastHistoryOrderTime().join();
            IsoTime startingHistoryOrderTime;
            if (historyStartTime == null || lastHistoryOrderTime.getDate().compareTo(historyStartTime.getDate()) > 0) {
                startingHistoryOrderTime = lastHistoryOrderTime;
            } else startingHistoryOrderTime = historyStartTime;
            IsoTime lastDealTime = historyStorage.getLastDealTime().join();
            IsoTime startingDealTime;
            if (historyStartTime == null || lastDealTime.getDate().compareTo(historyStartTime.getDate()) > 0) {
                startingDealTime = lastDealTime;
            } else startingDealTime = historyStartTime;
            lastSynchronizationId = UUID.randomUUID().toString();
            websocketClient.synchronize(
                account.getId(), lastSynchronizationId,
                startingHistoryOrderTime, startingDealTime
            ).join();
        });
    }
    
    /**
     * Initializes meta api connection
     * @return completable future which resolves when meta api connection is initialized
     */
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            historyStorage.loadData().join();
        });
    }
    
    /**
     * Initiates subscription to MetaTrader terminal
     * @return completable future which resolves when subscription is initiated
     */
    public CompletableFuture<Void> subscribe() {
        return websocketClient.subscribe(account.getId()).exceptionally(err -> {
            if (!(err instanceof TimeoutException)) {
                throw new CompletionException(err);
            }
            return null;
        });
    }
    
    /**
     * Subscribes on market data of specified symbol (see
     * https://metaapi.cloud/docs/client/websocket/marketDataStreaming/subscribeToMarketData/).
     * @param symbol symbol (e.g. currency pair or an index)
     * @return completable future which resolves when subscription request was processed
     */
    public CompletableFuture<Void> subscribeToMarketData(String symbol) {
        return CompletableFuture.runAsync(() -> {
            subscriptions.add(symbol);
            websocketClient.subscribeToMarketData(account.getId(), symbol).join();
        });
    }
    
    /**
     * Returns list of the symbols connection is subscribed to
     * @return list of the symbols connection is subscribed to
     */
    public List<String> getSubscribedSymbols() {
        return new ArrayList<>(subscriptions);
    }
    
    /**
     * Retrieves specification for a symbol (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveMarketData/getSymbolSpecification/).
     * @param symbol symbol to retrieve specification for
     * @return completable future which resolves with specification retrieved
     */
    public CompletableFuture<MetatraderSymbolSpecification> getSymbolSpecification(String symbol) {
        return websocketClient.getSymbolSpecification(account.getId(), symbol);
    }
    
    /**
     * Retrieves specification for a symbol (see
     * https://metaapi.cloud/docs/client/websocket/api/retrieveMarketData/getSymbolPrice/).
     * @param symbol symbol to retrieve price for
     * @return completable future which resolves with price retrieved
     */
    public CompletableFuture<MetatraderSymbolPrice> getSymbolPrice(String symbol) {
        return websocketClient.getSymbolPrice(account.getId(), symbol);
    }
    
    /**
     * Sends client uptime stats to the server.
     * @param uptime uptime statistics to send to the server
     * @return completable future which resolves when uptime statistics is submitted
     */
    public CompletableFuture<Void> saveUptime(Map<String, Double> uptime) {
        return websocketClient.saveUptime(account.getId(), uptime);
    }
    
    /**
     * Returns local copy of terminal state
     * @return local copy of terminal state
     */
    public TerminalState getTerminalState() {
        return terminalState;
    }
    
    /**
     * Returns local history storage
     * @return local history storage
     */
    public HistoryStorage getHistoryStorage() {
        return historyStorage;
    }
    
    /**
     * Adds synchronization listener
     * @param listener synchronization listener to add
     */
    public void addSynchronizationListener(SynchronizationListener listener)  {
        websocketClient.addSynchronizationListener(account.getId(), listener);
    }
    
    /**
     * Removes synchronization listener for specific account
     * @param listener synchronization listener to remove
     */
    public void removeSynchronizationListener(SynchronizationListener listener) {
        websocketClient.removeSynchronizationListener(account.getId(), listener);
    }
    
    @Override
    public CompletableFuture<Void> onConnected() {
        return CompletableFuture.runAsync(() -> {
            String key = RandomStringUtils.randomAlphanumeric(32);
            shouldSynchronize = key;
            synchronizationRetryIntervalInSeconds = 1;
            isSynchronized = false;
            ensureSynchronized(key);
        });
    }

    @Override
    public CompletableFuture<Void> onDisconnected() {
        lastDisconnectedSynchronizationId = lastSynchronizationId;
        lastSynchronizationId = null;
        shouldSynchronize = null;
        isSynchronized = false;
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onDealSynchronizationFinished(String synchronizationId) {
        return CompletableFuture.runAsync(() -> {
            dealsSynchronized.add(synchronizationId);
            historyStorage.updateStorage().join();
        });
    }
    
    @Override
    public CompletableFuture<Void> onOrderSynchronizationFinished(String synchronizationId) {
        ordersSynchronized.add(synchronizationId);
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<Void> onReconnected() {
        try {
            return subscribe();
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
    
    /**
     * Returns flag indicating status of state synchronization with MetaTrader terminal
     * @param synchronizationId optional synchronization request id, last synchronization 
     * request id will be used by default
     * @return completable future resolving with a flag indicating status of state synchronization
     * with MetaTrader terminal
     */
    public CompletableFuture<Boolean> isSynchronized(String synchronizationId) {
        if (synchronizationId == null) synchronizationId = lastSynchronizationId;
        return CompletableFuture.completedFuture(ordersSynchronized.contains(synchronizationId) 
            && dealsSynchronized.contains(synchronizationId));
    }
    
    /**
     * Waits until synchronization to MetaTrader terminal is completed. Completes exceptionally with TimeoutError 
     * if application failed to synchronize with the teminal withing timeout allowed.
     * @return completable future which resolves when synchronization to MetaTrader terminal is completed
     */
    public CompletableFuture<Void> waitSynchronized() {
        return waitSynchronized(null);
    }
    
    /**
     * Waits until synchronization to MetaTrader terminal is completed. Completes exceptionally with TimeoutError 
     * if application failed to synchronize with the teminal withing timeout allowed.
     * @param opts synchronization options, or {@code null}
     * @return completable future which resolves when synchronization to MetaTrader terminal is completed
     */
    public CompletableFuture<Void> waitSynchronized(SynchronizationOptions opts) {
        if (opts == null) opts = new SynchronizationOptions();
        String synchronizationId = opts.synchronizationId;
        int timeoutInSeconds = (opts.timeoutInSeconds != null ? opts.timeoutInSeconds : 300);
        int intervalInMilliseconds = (opts.intervalInMilliseconds != null ? opts.intervalInMilliseconds : 1000);
        String applicationPattern = (opts.applicationPattern != null ? opts.applicationPattern :
            (account.getApplication().equals("CopyFactory") ? "CopyFactory.*|RPC" : "RPC"));
        long startTime = Instant.now().getEpochSecond();
        long timeoutTime = startTime + timeoutInSeconds;
        return CompletableFuture.runAsync(() -> {
            try {
                boolean isSynchronized;
                while (!(isSynchronized = isSynchronized(synchronizationId).get())
                    && timeoutTime > Instant.now().getEpochSecond()) {
                    Thread.sleep(intervalInMilliseconds);
                };
                if (!isSynchronized) throw new TimeoutException(
                    "Timed out waiting for account MetApi to synchronize to MetaTrader account " 
                    + account.getId() + ", synchronization id " + (
                        synchronizationId != null ? synchronizationId
                        : (lastSynchronizationId != null ? lastSynchronizationId 
                            : lastDisconnectedSynchronizationId)
                    )
                );
                websocketClient.waitSynchronized(account.getId(), applicationPattern, (long) timeoutInSeconds).get();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }
    
    /**
     * Closes the connection. The instance of the class should no longer be used after this method is invoked.
     * @return completable future resolving when connection is closed
     */
    public CompletableFuture<Void> close() {
        return CompletableFuture.runAsync(() -> {
            if (!closed) {
                shouldSynchronize = null;
                websocketClient.unsubscribe(account.getId()).join();
                websocketClient.removeSynchronizationListener(account.getId(), this);
                websocketClient.removeSynchronizationListener(account.getId(), terminalState);
                websocketClient.removeSynchronizationListener(account.getId(), historyStorage);
                websocketClient.removeSynchronizationListener(account.getId(), healthMonitor);
                connectionRegistry.remove(account.getId());
                healthMonitor.stop();
                closed = true;
            }
        });
    }
    
    /**
     * Returns synchronization status
     * @return synchronization status
     */
    public boolean isSynchronized() {
        return isSynchronized;
    }
    
    /**
     * Returns MetaApi account
     * @return MetaApi account
     */
    public MetatraderAccount getAccount() {
        return account;
    }
    
    /**
     * Returns connection health monitor instance
     * @return connection health monitor instance
     */
    public ConnectionHealthMonitor getHealthMonitor() {
        return healthMonitor;
    }
    
    private void ensureSynchronized(String key) {
        try {
            synchronize().join();
            for (String symbol : subscriptions) {
                subscribeToMarketData(symbol);
            }
            isSynchronized = true;
            synchronizationRetryIntervalInSeconds = 1;
        } catch (CompletionException e) {
            logger.error("MetaApi websocket client for account " + account.getId()
                + " failed to synchronize", e.getCause());
            if (shouldSynchronize.equals(key)) {
                Timer retryTimer = new Timer();
                MetaApiConnection self = this;
                retryTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        self.ensureSynchronized(key);
                    }
                }, 1000 * synchronizationRetryIntervalInSeconds);
                synchronizationRetryIntervalInSeconds = Math.min(synchronizationRetryIntervalInSeconds * 2, 300);
            }
        }
    }
    
    private void copyModelProperties(Object source, Object target) {
        Field[] publicFields = source.getClass().getFields();
        for (int i = 0; i < publicFields.length; ++i) {
            Field sourceField = publicFields[i];
            try {
                Field targetField = target.getClass().getField(sourceField.getName());
                targetField.set(target, sourceField.get(source));
            } catch (NoSuchFieldException e) {
                // Just pass this field
            } catch (Exception e) {
                logger.error("Cannot copy model property " + sourceField.getName(), e);
            }
        }
    }
}