package cloud.metaapi.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import cloud.metaapi.sdk.clients.MetaApiWebsocketClient;
import cloud.metaapi.sdk.clients.MetatraderAccountClient;
import cloud.metaapi.sdk.clients.models.NewMetatraderAccountDto;

/**
 * Exposes MetaTrader account API logic to the consumers
 */
public class MetatraderAccountApi {
    
    private MetatraderAccountClient metatraderAccountClient;
    private MetaApiWebsocketClient metaApiWebsocketClient;
    
    /**
     * Constructs a MetaTrader account API instance
     * @param metatraderAccountClient MetaTrader account REST API client
     * @param metaApiWebsocketClient MetaApi websocket client
     */
    public MetatraderAccountApi(
        MetatraderAccountClient metatraderAccountClient,
        MetaApiWebsocketClient metaApiWebsocketClient
    ) {
        this.metatraderAccountClient = metatraderAccountClient;
        this.metaApiWebsocketClient = metaApiWebsocketClient;
    }
    
    /**
     * Retrieves MetaTrader accounts
     * @param provisioningProfileId provisioning profile id
     * @return completable future resolving with a list of MetaTrader account entities
     */
    public CompletableFuture<List<MetatraderAccount>> getAccounts(
        Optional<String> provisioningProfileId
    ) throws Exception {
        return metatraderAccountClient.getAccounts(provisioningProfileId).thenApply(accounts -> {
            List<MetatraderAccount> result = new ArrayList<>();
            accounts.forEach(accountDto -> result.add(
                new MetatraderAccount(accountDto, metatraderAccountClient, metaApiWebsocketClient)
            ));
            return result;
        });
    }
    
    /**
     * Retrieves a MetaTrader account by id
     * @param accountId MetaTrader account id
     * @return completable future resolving with MetaTrader account entity
     */
    public CompletableFuture<MetatraderAccount> getAccount(String accountId) throws Exception {
        return metatraderAccountClient.getAccount(accountId).thenApply(accountDto -> {
            return new MetatraderAccount(accountDto, metatraderAccountClient, metaApiWebsocketClient);
        });
    }
    
    /**
     * Creates a MetaTrader account
     * @param account MetaTrader account data
     * @return completable future resolving with MetaTrader account entity
     */
    public CompletableFuture<MetatraderAccount> createAccount(NewMetatraderAccountDto account) throws Exception {
        return metatraderAccountClient.createAccount(account).thenApply(id -> {
            try {
                return getAccount(id.id).get();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }
}