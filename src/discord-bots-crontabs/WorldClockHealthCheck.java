package com.max480.discord.randombots;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.FileUpload;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DecimalFormat;
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
        public ReplyCallbackAction closeResources() {
            return null;
        }

        public JDA getJDA() {
            return null;
        }

        public ReplyCallbackAction setCheck(@Nullable BooleanSupplier booleanSupplier) {
            return null;
        }

        public void queue(@Nullable Consumer<? super InteractionHook> consumer, @Nullable Consumer<? super Throwable> consumer1) {
        }

        public InteractionHook complete(boolean b) throws RateLimitedException {
            return null;
        }

        public CompletableFuture<InteractionHook> submit(boolean b) {
            return null;
        }

        public ReplyCallbackAction addContent(@NotNull String s) {
            return null;
        }

        public ReplyCallbackAction addEmbeds(@NotNull Collection<? extends MessageEmbed> collection) {
            return null;
        }

        public ReplyCallbackAction addComponents(@NotNull Collection<? extends LayoutComponent> collection) {
            return null;
        }

        public ReplyCallbackAction addFiles(@NotNull Collection<? extends FileUpload> collection) {
            return null;
        }

        public String getContent() {
            return null;
        }

        public List<MessageEmbed> getEmbeds() {
            return null;
        }

        public List<LayoutComponent> getComponents() {
            return null;
        }

        public List<FileUpload> getAttachments() {
            return null;
        }

        public boolean isSuppressEmbeds() {
            return false;
        }

        public Set<String> getMentionedUsers() {
            return null;
        }

        public Set<String> getMentionedRoles() {
            return null;
        }

        public EnumSet<Message.MentionType> getAllowedMentions() {
            return null;
        }

        public boolean isMentionRepliedUser() {
            return false;
        }

        public ReplyCallbackAction setTTS(boolean b) {
            return null;
        }

        public ReplyCallbackAction setContent(@Nullable String s) {
            return null;
        }

        public ReplyCallbackAction setEmbeds(@NotNull Collection<? extends MessageEmbed> collection) {
            return null;
        }

        public ReplyCallbackAction setComponents(@NotNull Collection<? extends LayoutComponent> collection) {
            return null;
        }

        public ReplyCallbackAction setSuppressEmbeds(boolean b) {
            return null;
        }

        public ReplyCallbackAction setFiles(@Nullable Collection<? extends FileUpload> collection) {
            return null;
        }

        public ReplyCallbackAction mentionRepliedUser(boolean b) {
            return null;
        }

        public ReplyCallbackAction setAllowedMentions(@Nullable Collection<Message.MentionType> collection) {
            return null;
        }

        public ReplyCallbackAction mention(@NotNull Collection<? extends IMentionable> collection) {
            return null;
        }

        public ReplyCallbackAction mentionUsers(@NotNull Collection<String> collection) {
            return null;
        }

        public ReplyCallbackAction mentionRoles(@NotNull Collection<String> collection) {
            return null;
        }
        // </editor-fold>

        @NotNull
        @Override
        public ReplyCallbackAction setEphemeral(boolean b) {
            return this;
        }
    }

    /**
     * Mock IReplyCallback that does nothing except capturing the reply that was passed.
     */
    private static class MockCallback implements IReplyCallback {
        // <editor-fold desc="Stub Methods">
        public ReplyCallbackAction deferReply() {
            return null;
        }

        public InteractionHook getHook() {
            return null;
        }

        public int getTypeRaw() {
            return 0;
        }

        public String getToken() {
            return null;
        }

        public Guild getGuild() {
            return null;
        }

        public User getUser() {
            return null;
        }

        public Member getMember() {
            return null;
        }

        public boolean isAcknowledged() {
            return false;
        }

        public Channel getChannel() {
            return null;
        }

        public DiscordLocale getUserLocale() {
            return null;
        }

        public JDA getJDA() {
            return null;
        }

        public long getIdLong() {
            return 0;
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
        BotEventListener.giveTimeForOtherPlace(callback, null, "washington", DiscordLocale.ENGLISH_US);

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
        List<Command.Choice> choices = BotEventListener.INSTANCE.suggestTimezones("cest", DiscordLocale.ENGLISH_US);

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
