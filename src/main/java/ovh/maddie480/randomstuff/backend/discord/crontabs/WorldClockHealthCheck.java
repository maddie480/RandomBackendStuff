package ovh.maddie480.randomstuff.backend.discord.crontabs;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.MessageTopLevelComponentUnion;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.*;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessagePollData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.discord.timezonebot.BotEventListener;

import java.io.IOException;
import java.text.DecimalFormat;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Checks that the Timezone Bot commands that involve external APIs still work.
 * Run daily.
 */
public class WorldClockHealthCheck {
    private static final Logger logger = LoggerFactory.getLogger(WorldClockHealthCheck.class);

    /**
     * Mock ReplyCallbackAction that does nothing.
     */
    private static class MockReplyCallbackAction implements ReplyCallbackAction {
        // <editor-fold desc="Stub Methods">
        @NotNull
        @Override
        public ReplyCallbackAction closeResources() {
            return null;
        }

        @NotNull
        @Override
        public JDA getJDA() {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setCheck(@Nullable BooleanSupplier booleanSupplier) {
            return null;
        }

        @Override
        public void queue(@Nullable Consumer<? super InteractionHook> consumer, @Nullable Consumer<? super Throwable> consumer1) {
        }

        @Override
        public InteractionHook complete(boolean b) {
            return null;
        }

        @NotNull
        @Override
        public CompletableFuture<InteractionHook> submit(boolean b) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction addContent(@NotNull String s) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction addEmbeds(@NotNull Collection<? extends MessageEmbed> collection) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction addComponents(@NotNull Collection<? extends MessageTopLevelComponent> collection) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction addFiles(@NotNull Collection<? extends FileUpload> collection) {
            return null;
        }

        @NotNull
        @Override
        public String getContent() {
            return null;
        }

        @NotNull
        @Override
        public List<MessageEmbed> getEmbeds() {
            return null;
        }

        @Override
        public @NotNull List<MessageTopLevelComponentUnion> getComponents() {
            return List.of();
        }

        @Override
        public boolean isUsingComponentsV2() {
            return false;
        }

        @NotNull
        @Override
        public List<FileUpload> getAttachments() {
            return null;
        }

        @Nullable
        @Override
        public MessagePollData getPoll() {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setPoll(@Nullable MessagePollData messagePollData) {
            return null;
        }

        @Override
        public boolean isSuppressEmbeds() {
            return false;
        }

        @NotNull
        @Override
        public Set<String> getMentionedUsers() {
            return null;
        }

        @NotNull
        @Override
        public Set<String> getMentionedRoles() {
            return null;
        }

        @NotNull
        @Override
        public EnumSet<Message.MentionType> getAllowedMentions() {
            return null;
        }

        @Override
        public boolean isMentionRepliedUser() {
            return false;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setTTS(boolean b) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setContent(@Nullable String s) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setEmbeds(@NotNull Collection<? extends MessageEmbed> collection) {
            return null;
        }

        @Override
        public @NotNull ReplyCallbackAction setComponents(@NotNull Collection<? extends MessageTopLevelComponent> collection) {
            return null;
        }

        @Override
        public @NotNull ReplyCallbackAction useComponentsV2(boolean b) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setSuppressEmbeds(boolean b) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setSuppressedNotifications(boolean b) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setFiles(@Nullable Collection<? extends FileUpload> collection) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction mentionRepliedUser(boolean b) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setAllowedMentions(@Nullable Collection<Message.MentionType> collection) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction mention(@NotNull Collection<? extends IMentionable> collection) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction mentionUsers(@NotNull Collection<String> collection) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction mentionRoles(@NotNull Collection<String> collection) {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setEphemeral(boolean b) {
            return this;
        }

        @NotNull
        @Override
        public ReplyCallbackAction setVoiceMessage(boolean voiceMessage) {
            return this;
        }
        // </editor-fold>
    }

    /**
     * Mock IReplyCallback that does nothing except capturing the reply that was passed.
     */
    private static class MockCallback implements IReplyCallback {
        // <editor-fold desc="Stub Methods">
        @NotNull
        @Override
        public ReplyCallbackAction deferReply() {
            return null;
        }

        @NotNull
        @Override
        public ReplyCallbackAction deferReply(boolean ephemeral) {
            return IReplyCallback.super.deferReply(ephemeral);
        }

        @NotNull
        @Override
        public ReplyCallbackAction reply(@NotNull MessageCreateData message) {
            return IReplyCallback.super.reply(message);
        }

        @NotNull
        @Override
        public InteractionHook getHook() {
            return null;
        }

        @Override
        public int getTypeRaw() {
            return 0;
        }

        @NotNull
        @Override
        public InteractionType getType() {
            return IReplyCallback.super.getType();
        }

        @NotNull
        @Override
        public String getToken() {
            return null;
        }

        @Override
        public Guild getGuild() {
            return null;
        }

        @Override
        public boolean isFromGuild() {
            return IReplyCallback.super.isFromGuild();
        }

        @NotNull
        @Override
        public ChannelType getChannelType() {
            return IReplyCallback.super.getChannelType();
        }

        @NotNull
        @Override
        public User getUser() {
            return null;
        }

        @Override
        public Member getMember() {
            return null;
        }

        @Override
        public boolean isAcknowledged() {
            return false;
        }

        @Override
        public Channel getChannel() {
            return null;
        }

        @Override
        public long getChannelIdLong() {
            return 0;
        }

        @Nullable
        @Override
        public String getChannelId() {
            return IReplyCallback.super.getChannelId();
        }

        @NotNull
        @Override
        public GuildChannel getGuildChannel() {
            return IReplyCallback.super.getGuildChannel();
        }

        @NotNull
        @Override
        public MessageChannel getMessageChannel() {
            return IReplyCallback.super.getMessageChannel();
        }

        @NotNull
        @Override
        public DiscordLocale getUserLocale() {
            return null;
        }

        @NotNull
        @Override
        public DiscordLocale getGuildLocale() {
            return IReplyCallback.super.getGuildLocale();
        }

        @NotNull
        @Override
        public List<Entitlement> getEntitlements() {
            return Collections.emptyList();
        }

        @NotNull
        @Override
        public InteractionContextType getContext() {
            return null;
        }

        @NotNull
        @Override
        public IntegrationOwners getIntegrationOwners() {
            return null;
        }

        @NotNull
        @Override
        public JDA getJDA() {
            return null;
        }

        @NotNull
        @Override
        public String getId() {
            return IReplyCallback.super.getId();
        }

        @Override
        public long getIdLong() {
            return 0;
        }

        @NotNull
        @Override
        public OffsetDateTime getTimeCreated() {
            return IReplyCallback.super.getTimeCreated();
        }


        @NotNull
        @Override
        public ReplyCallbackAction replyEmbeds(@NotNull Collection<? extends MessageEmbed> embeds) {
            return IReplyCallback.super.replyEmbeds(embeds);
        }

        @NotNull
        @Override
        public ReplyCallbackAction replyEmbeds(@NotNull MessageEmbed embed, @NotNull MessageEmbed... embeds) {
            return IReplyCallback.super.replyEmbeds(embed, embeds);
        }

        @NotNull
        @Override
        public ReplyCallbackAction replyFormat(@NotNull String format, @NotNull Object... args) {
            return IReplyCallback.super.replyFormat(format, args);
        }

        @NotNull
        @Override
        public ReplyCallbackAction replyFiles(@NotNull Collection<? extends FileUpload> files) {
            return IReplyCallback.super.replyFiles(files);
        }

        @NotNull
        @Override
        public ReplyCallbackAction replyFiles(@NotNull FileUpload... files) {
            return IReplyCallback.super.replyFiles(files);
        }
        // </editor-fold>

        private String response = null;

        @NotNull
        @Override
        public ReplyCallbackAction reply(@NotNull String content) {
            response = content;
            return new MockReplyCallbackAction();
        }

        public String getResponse() {
            return response;
        }
    }

    public static void main(String[] args) throws IOException {
        // /world-clock health check
        MockCallback callback = new MockCallback();
        BotEventListener.giveTimeForOtherPlace(callback, null, "washington dc", DiscordLocale.ENGLISH_US);

        ZonedDateTime nowAtPlace = ZonedDateTime.now(ZoneId.of("America/New_York"));
        DateTimeFormatter format = DateTimeFormatter.ofPattern("MMM dd, HH:mm", Locale.ENGLISH);
        int consideredTimezoneOffsetHours = nowAtPlace.getOffset().getTotalSeconds() / 3600;

        String expected = "The current time in **Washington, District of Columbia, United States** is **"
                + nowAtPlace.format(format) + "** (UTC" + new DecimalFormat("00").format(consideredTimezoneOffsetHours) + ":00).";

        logger.debug("Expected: {}, Actual: {}", expected, callback.getResponse());

        if (!expected.equals(callback.getResponse())) {
            throw new IOException("/world-clock did not return the expected response!");
        }


        // timezone autocomplete health check (checks if fetching from timeanddate.com still works)
        List<Command.Choice> choices = new BotEventListener().suggestTimezones("cest", DiscordLocale.ENGLISH_US);

        ZonedDateTime nowInZone = ZonedDateTime.now(ZoneId.of("UTC+2"));
        format = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH);

        List<Command.Choice> expectedChoice = Collections.singletonList(
                new Command.Choice("Central European Summer Time (CEST) (" + nowInZone.format(format).toLowerCase(Locale.ROOT) + ")", "UTC+2")
        );

        logger.debug("Expected: {}, actual: {}", expectedChoice, choices);

        if (!expectedChoice.equals(choices)) {
            throw new IOException("Timezone suggestion did not return the expected response!");
        }
    }
}
