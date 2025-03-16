package ovh.maddie480.randomstuff.backend.utils;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.*;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.entities.sticker.StickerPack;
import net.dv8tion.jda.api.entities.sticker.StickerSnowflake;
import net.dv8tion.jda.api.entities.sticker.StickerUnion;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.managers.DirectAudioController;
import net.dv8tion.jda.api.managers.Presence;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.*;
import net.dv8tion.jda.api.requests.restaction.pagination.EntitlementPaginationAction;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.Once;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.cache.CacheView;
import net.dv8tion.jda.api.utils.cache.ChannelCacheView;
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView;
import okhttp3.OkHttpClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DiscardableJDA implements JDA, Closeable {
    private static final Logger log = LoggerFactory.getLogger(DiscardableJDA.class);

    public DiscardableJDA(String token, GatewayIntent intent, GatewayIntent... intents) {
        try {
            backingJDA = JDABuilder.createLight(token, intent, intents).build().awaitReady();
            log.info("Discardable JDA started with intents {}!", EnumSet.of(intent, intents));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public DiscardableJDA(String token) {
        try {
            backingJDA = JDABuilder.createLight(token, Collections.emptySet()).build().awaitReady();
            log.info("Discardable JDA started with no intents!");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        shutdown();
        log.info("Discardable JDA stopped!");
    }

    // <editor-fold desc="Methods Redirected to backingJDA">
    private final JDA backingJDA;

    @NotNull
    @Override
    public Status getStatus() {
        return backingJDA.getStatus();
    }

    @NotNull
    @Override
    public EnumSet<GatewayIntent> getGatewayIntents() {
        return backingJDA.getGatewayIntents();
    }

    @NotNull
    @Override
    public EnumSet<CacheFlag> getCacheFlags() {
        return backingJDA.getCacheFlags();
    }

    @Override
    public boolean unloadUser(long userId) {
        return backingJDA.unloadUser(userId);
    }

    @Override
    public long getGatewayPing() {
        return backingJDA.getGatewayPing();
    }

    @NotNull
    @Override
    public JDA awaitStatus(@NotNull Status status, Status @NotNull ... failOn) throws InterruptedException {
        return backingJDA.awaitStatus(status, failOn);
    }

    @Override
    public boolean awaitShutdown(long duration, @NotNull TimeUnit unit) throws InterruptedException {
        return backingJDA.awaitShutdown(duration, unit);
    }

    @Override
    public int cancelRequests() {
        return backingJDA.cancelRequests();
    }

    @NotNull
    @Override
    public ScheduledExecutorService getRateLimitPool() {
        return backingJDA.getRateLimitPool();
    }

    @NotNull
    @Override
    public ScheduledExecutorService getGatewayPool() {
        return backingJDA.getGatewayPool();
    }

    @NotNull
    @Override
    public ExecutorService getCallbackPool() {
        return backingJDA.getCallbackPool();
    }

    @NotNull
    @Override
    public OkHttpClient getHttpClient() {
        return backingJDA.getHttpClient();
    }

    @NotNull
    @Override
    public DirectAudioController getDirectAudioController() {
        return backingJDA.getDirectAudioController();
    }

    @Override
    public void setEventManager(@Nullable IEventManager manager) {
        backingJDA.setEventManager(manager);
    }

    @Override
    public void addEventListener(Object @NotNull ... listeners) {
        backingJDA.addEventListener(listeners);
    }

    @Override
    public void removeEventListener(Object @NotNull ... listeners) {
        backingJDA.removeEventListener(listeners);
    }

    @NotNull
    @Override
    public List<Object> getRegisteredListeners() {
        return backingJDA.getRegisteredListeners();
    }

    @NotNull
    @Override
    public <E extends GenericEvent> Once.Builder<E> listenOnce(@NotNull Class<E> aClass) {
        return backingJDA.listenOnce(aClass);
    }

    @NotNull
    @Override
    public RestAction<List<Command>> retrieveCommands(boolean withLocalizations) {
        return backingJDA.retrieveCommands(withLocalizations);
    }

    @NotNull
    @Override
    public RestAction<Command> retrieveCommandById(@NotNull String id) {
        return backingJDA.retrieveCommandById(id);
    }

    @NotNull
    @Override
    public RestAction<Command> upsertCommand(@NotNull CommandData command) {
        return backingJDA.upsertCommand(command);
    }

    @NotNull
    @Override
    public CommandListUpdateAction updateCommands() {
        return backingJDA.updateCommands();
    }

    @NotNull
    @Override
    public CommandEditAction editCommandById(@NotNull String id) {
        return backingJDA.editCommandById(id);
    }

    @NotNull
    @Override
    public RestAction<Void> deleteCommandById(@NotNull String commandId) {
        return backingJDA.deleteCommandById(commandId);
    }

    @NotNull
    @Override
    public RestAction<List<RoleConnectionMetadata>> retrieveRoleConnectionMetadata() {
        return backingJDA.retrieveRoleConnectionMetadata();
    }

    @NotNull
    @Override
    public RestAction<List<RoleConnectionMetadata>> updateRoleConnectionMetadata(@NotNull Collection<? extends RoleConnectionMetadata> records) {
        return backingJDA.updateRoleConnectionMetadata(records);
    }

    @NotNull
    @Override
    public GuildAction createGuild(@NotNull String name) {
        return backingJDA.createGuild(name);
    }

    @NotNull
    @Override
    public RestAction<Void> createGuildFromTemplate(@NotNull String code, @NotNull String name, @Nullable Icon icon) {
        return backingJDA.createGuildFromTemplate(code, name, icon);
    }

    @NotNull
    @Override
    public CacheView<AudioManager> getAudioManagerCache() {
        return backingJDA.getAudioManagerCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<User> getUserCache() {
        return backingJDA.getUserCache();
    }

    @NotNull
    @Override
    public List<Guild> getMutualGuilds(User @NotNull ... users) {
        return backingJDA.getMutualGuilds(users);
    }

    @NotNull
    @Override
    public List<Guild> getMutualGuilds(@NotNull Collection<User> users) {
        return backingJDA.getMutualGuilds(users);
    }

    @NotNull
    @Override
    public CacheRestAction<User> retrieveUserById(long id) {
        return backingJDA.retrieveUserById(id);
    }

    @NotNull
    @Override
    public SnowflakeCacheView<Guild> getGuildCache() {
        return backingJDA.getGuildCache();
    }

    @NotNull
    @Override
    public Set<String> getUnavailableGuilds() {
        return backingJDA.getUnavailableGuilds();
    }

    @Override
    public boolean isUnavailable(long guildId) {
        return backingJDA.isUnavailable(guildId);
    }

    @NotNull
    @Override
    public SnowflakeCacheView<Role> getRoleCache() {
        return backingJDA.getRoleCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<ScheduledEvent> getScheduledEventCache() {
        return backingJDA.getScheduledEventCache();
    }

    @NotNull
    @Override
    public ChannelCacheView<Channel> getChannelCache() {
        return backingJDA.getChannelCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<PrivateChannel> getPrivateChannelCache() {
        return backingJDA.getPrivateChannelCache();
    }

    @NotNull
    @Override
    public CacheRestAction<PrivateChannel> openPrivateChannelById(long userId) {
        return backingJDA.openPrivateChannelById(userId);
    }

    @NotNull
    @Override
    public SnowflakeCacheView<RichCustomEmoji> getEmojiCache() {
        return backingJDA.getEmojiCache();
    }

    @Override
    public @NotNull RestAction<ApplicationEmoji> createApplicationEmoji(@NotNull String s, @NotNull Icon icon) {
        return backingJDA.createApplicationEmoji(s, icon);
    }

    @Override
    public @NotNull RestAction<List<ApplicationEmoji>> retrieveApplicationEmojis() {
        return backingJDA.retrieveApplicationEmojis();
    }

    @Override
    public @NotNull RestAction<ApplicationEmoji> retrieveApplicationEmojiById(@NotNull String s) {
        return backingJDA.retrieveApplicationEmojiById(s);
    }

    @NotNull
    @Override
    public RestAction<StickerUnion> retrieveSticker(@NotNull StickerSnowflake sticker) {
        return backingJDA.retrieveSticker(sticker);
    }

    @NotNull
    @Override
    public RestAction<List<StickerPack>> retrieveNitroStickerPacks() {
        return backingJDA.retrieveNitroStickerPacks();
    }

    @NotNull
    @Override
    public IEventManager getEventManager() {
        return backingJDA.getEventManager();
    }

    @NotNull
    @Override
    public SelfUser getSelfUser() {
        return backingJDA.getSelfUser();
    }

    @NotNull
    @Override
    public Presence getPresence() {
        return backingJDA.getPresence();
    }

    @NotNull
    @Override
    public ShardInfo getShardInfo() {
        return backingJDA.getShardInfo();
    }

    @NotNull
    @Override
    public String getToken() {
        return backingJDA.getToken();
    }

    @Override
    public long getResponseTotal() {
        return backingJDA.getResponseTotal();
    }

    @Override
    public int getMaxReconnectDelay() {
        return backingJDA.getMaxReconnectDelay();
    }

    @Override
    public void setAutoReconnect(boolean reconnect) {
        backingJDA.setAutoReconnect(reconnect);
    }

    @Override
    public void setRequestTimeoutRetry(boolean retryOnTimeout) {
        backingJDA.setRequestTimeoutRetry(retryOnTimeout);
    }

    @Override
    public boolean isAutoReconnect() {
        return backingJDA.isAutoReconnect();
    }

    @Override
    public boolean isBulkDeleteSplittingEnabled() {
        return backingJDA.isBulkDeleteSplittingEnabled();
    }

    @Override
    public void shutdown() {
        backingJDA.shutdown();
    }

    @Override
    public void shutdownNow() {
        backingJDA.shutdownNow();
    }

    @NotNull
    @Override
    public RestAction<ApplicationInfo> retrieveApplicationInfo() {
        return backingJDA.retrieveApplicationInfo();
    }

    @NotNull
    @Override
    public EntitlementPaginationAction retrieveEntitlements() {
        return backingJDA.retrieveEntitlements();
    }

    @NotNull
    @Override
    public RestAction<Entitlement> retrieveEntitlementById(long l) {
        return backingJDA.retrieveEntitlementById(l);
    }

    @NotNull
    @Override
    public TestEntitlementCreateAction createTestEntitlement(long l, long l1, @NotNull TestEntitlementCreateAction.OwnerType ownerType) {
        return backingJDA.createTestEntitlement(l, l1, ownerType);
    }

    @NotNull
    @Override
    public RestAction<Void> deleteTestEntitlement(long l) {
        return backingJDA.deleteTestEntitlement(l);
    }

    @NotNull
    @Override
    public JDA setRequiredScopes(@NotNull Collection<String> scopes) {
        return backingJDA.setRequiredScopes(scopes);
    }

    @NotNull
    @Override
    public String getInviteUrl(@Nullable Permission... permissions) {
        return backingJDA.getInviteUrl(permissions);
    }

    @NotNull
    @Override
    public String getInviteUrl(@Nullable Collection<Permission> permissions) {
        return backingJDA.getInviteUrl(permissions);
    }

    @Nullable
    @Override
    public ShardManager getShardManager() {
        return backingJDA.getShardManager();
    }

    @NotNull
    @Override
    public RestAction<Webhook> retrieveWebhookById(@NotNull String webhookId) {
        return backingJDA.retrieveWebhookById(webhookId);
    }

    @NotNull
    @Override
    public SnowflakeCacheView<StageChannel> getStageChannelCache() {
        return backingJDA.getStageChannelCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<ThreadChannel> getThreadChannelCache() {
        return backingJDA.getThreadChannelCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<Category> getCategoryCache() {
        return backingJDA.getCategoryCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<TextChannel> getTextChannelCache() {
        return backingJDA.getTextChannelCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<NewsChannel> getNewsChannelCache() {
        return backingJDA.getNewsChannelCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<VoiceChannel> getVoiceChannelCache() {
        return backingJDA.getVoiceChannelCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<ForumChannel> getForumChannelCache() {
        return backingJDA.getForumChannelCache();
    }

    @NotNull
    @Override
    public SnowflakeCacheView<MediaChannel> getMediaChannelCache() {
        return backingJDA.getMediaChannelCache();
    }
    // </editor-fold>
}
