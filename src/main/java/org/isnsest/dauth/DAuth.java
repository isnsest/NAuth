package org.isnsest.dauth;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DAuth extends JavaPlugin {

    private Database database;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        database = new Database(this);
        Bukkit.getPluginManager().registerEvents(new EventListener(this), this);

        Metrics metrics = new Metrics(this, 28317);
    }

    @Override
    public void onDisable() {}

    public Database db() {
        return database;
    }

    private enum AuthMode {
        LOGIN,
        REGISTER,
        CHANGE
    }

    private static class EventListener implements Listener {

        private final DAuth plugin;

        private final Map<UUID, Integer> wrongs = new HashMap<>();
        private final Map<UUID, CompletableFuture<String>> awaitingResponse = new ConcurrentHashMap<>();

        private static final ClickCallback.Options options =
                ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build();

        public EventListener(DAuth plugin) {
            this.plugin = plugin;

            plugin.getCommand("dreload").setExecutor((sender, command, label, args) -> {
                sender.sendMessage(ChatColor.GREEN + "The configuration has been reloaded.");
                plugin.reloadConfig();
                return true;
            });
            plugin.getCommand("changepassword").setExecutor((sender, cmd, lbl, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players.");
                    return true;
                }

                UUID uuid = player.getUniqueId();

                if (plugin.db().getPassword(uuid) == null) {
                    player.sendMessage(ChatColor.RED + "You are not registered.");
                    return true;
                }

                player.showDialog(
                        createDialog(
                                AuthMode.CHANGE,
                                Component.text(mes("title-change", "Change your password")),
                                uuid
                        )
                );
                return true;
            });
        }

        //

        @EventHandler
        public void onPlayerConfigure(AsyncPlayerConnectionConfigureEvent event) {

            PlayerConfigurationConnection connection = event.getConnection();
            UUID uniqueId = connection.getProfile().getId();
            if (uniqueId == null) {
                return;
            }

            AuthMode mode = plugin.db().getPassword(uniqueId) == null
                    ? AuthMode.REGISTER : AuthMode.LOGIN;

            Dialog dialog = createDialog(
                    mode,
                    Component.text(mes("title", "Authentication is required to join the server.")),
                    uniqueId
            );

            int timeout = plugin.getConfig().getInt("limits.timeout-seconds", 60);

            CompletableFuture<String> response = new CompletableFuture<>();
            response.completeOnTimeout("Timeout", timeout, TimeUnit.SECONDS);

            awaitingResponse.put(uniqueId, response);

            Audience audience = connection.getAudience();
            audience.showDialog(dialog);

            switch (response.join()) {
                case "Timeout" -> {
                    audience.closeDialog();
                    connection.disconnect(Component.text(mes("error-timeout", "Authentication timeout.")));
                }
                case "Wrong password" -> {
                    audience.closeDialog();
                    connection.disconnect(Component.text(mes("error-wrong-password", "Wrong password.")));
                }
                case "Exit" -> {
                    audience.closeDialog();
                    connection.disconnect(Component.text(mes("quit", "You have left the server.")));
                }
                case "Done" -> audience.closeDialog();
            }

            awaitingResponse.remove(uniqueId);
            wrongs.remove(uniqueId);
        }

        //

        @EventHandler
        public void onLogin(PlayerLoginEvent event) {
            if (Bukkit.getOnlinePlayers().contains(event.getPlayer())) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "This account is already on the server.");
            }
        }

        //

        @EventHandler
        void onConnectionClose(PlayerConnectionCloseEvent event) {
            awaitingResponse.remove(event.getPlayerUniqueId());
            wrongs.remove(event.getPlayerUniqueId());
        }

        //

        private Dialog createDialog(AuthMode mode, Component title, UUID uuid) {

            int max = plugin.getConfig().getInt("limits.max-password-length", 32);
            List<DialogInput> inputs = List.of();

            boolean canCloseWithEscape = false;

            switch (mode) {
                case LOGIN -> inputs = List.of(
                        DialogInput.text("password1", 150,
                                Component.text(mes("password-label", "Password")), true, "", max, null)
                );
                case REGISTER -> inputs = List.of(
                        DialogInput.text("password1", 150,
                                Component.text(mes("password-label", "Password")), true, "", max, null),
                        DialogInput.text("password2", 150,
                                Component.text(mes("password-again-label", "Repeat password")), true, "", max, null)
                );
                case CHANGE -> {
                        canCloseWithEscape = true;
                        inputs = List.of(
                        DialogInput.text("old", 150,
                                Component.text(mes("old-password-label", "Old password")), true, "", max, null),
                        DialogInput.text("new1", 150,
                                Component.text(mes("new-password-label", "New password")), true, "", max, null),
                        DialogInput.text("new2", 150,
                                Component.text(mes("password-again-label", "Repeat password")), true, "", max, null)
                );
                }
            }

            String buttonText =
                    mode == AuthMode.LOGIN ? mes("button-login", "Login") :
                            mode == AuthMode.REGISTER ? mes("button-register", "Register") :
                                    mes("button-change", "Change");

            DialogBase base = DialogBase.builder(Component.text("DAuth"))
                    .inputs(inputs)
                    .body(List.of(DialogBody.plainMessage(title)))
                    .canCloseWithEscape(canCloseWithEscape)
                    .build();

            List<ActionButton> actionButtons = new ArrayList<>();
            actionButtons.add(ActionButton.create(
                    Component.text(buttonText),
                    null,
                    125,
                    DialogAction.customClick(
                            (view, audience) -> authPlayer(view, audience, uuid, mode),
                            options
                    )));
            if (!plugin.getConfig().getString("links.discord", "<link>").equals("<link>")) {
                try {
                    URI url = new URI(plugin.getConfig().getString("links.discord", "<link>"));
                    actionButtons.add(ActionButton.create(
                            Component.text(mes("button-discord", "Discord")),
                            null,
                            65,
                            DialogAction.staticAction(ClickEvent.openUrl(url.toURL()))));
                } catch (Exception ignored) {
                }
            }

            return Dialog.create(builder -> builder.empty().base(base)
                    .type(DialogType.multiAction(actionButtons
                    ).exitAction(
                            ActionButton.create(
                                    Component.text(mes("button-quit", "Quit")),
                                    null,
                                    50,
                                    DialogAction.customClick(
                                            (view, audience) -> setConnectionJoinResult(uuid, "Exit"),
                                            options
                                    )
                            )
                    ).build()));
        }

        //

        private void authPlayer(DialogResponseView view, Audience audience, UUID uuid, AuthMode mode) {

            int min = plugin.getConfig().getInt("limits.min-password-length", 1);
            int max = plugin.getConfig().getInt("limits.max-password-length", 32);

            if (mode == AuthMode.REGISTER) {

                String pass1 = view.getText("password1");
                String pass2 = view.getText("password2");

                if (pass1.length() < min || pass1.length() > max ||
                        pass2.length() < min || pass2.length() > max) {

                    audience.showDialog(createDialog(
                            AuthMode.REGISTER,
                            Component.text(
                                    mes("error-password-length",
                                            "Password must be between %min% and %max% characters."),
                                    TextColor.color(220, 20, 60)
                            ),
                            uuid
                    ));
                    return;
                }

                if (!Objects.equals(pass1, pass2) || pass1.contains(" ") || pass1.isBlank()) {
                    audience.showDialog(createDialog(
                            AuthMode.REGISTER,
                            Component.text(
                                    mes("error-password-mismatch", "Passwords do not match."),
                                    TextColor.color(220, 20, 60)
                            ),
                            uuid
                    ));
                    return;
                }

                plugin.db().saveUser(uuid, pass1);
                setConnectionJoinResult(uuid, "Done");
                return;
            }

            if (mode == AuthMode.LOGIN) {
                String pass1 = view.getText("password1");

                if (plugin.db().getPassword(uuid).equals(pass1)) {
                    setConnectionJoinResult(uuid, "Done");
                    return;
                }

                wrongs.merge(uuid, 1, Integer::sum);

                if (wrongs.get(uuid) >= 3) {
                    setConnectionJoinResult(uuid, "Wrong password");
                    return;
                }

                audience.showDialog(createDialog(
                        AuthMode.LOGIN,
                        Component.text(
                                mes("error-wrong-password", "Wrong password."),
                                TextColor.color(220, 20, 60)
                        ),
                        uuid
                ));
                return;
            }

            if (mode == AuthMode.CHANGE) {
                String oldReal = plugin.db().getPassword(uuid);

                String oldEntered = view.getText("old");
                String new1 = view.getText("new1");
                String new2 = view.getText("new2");

                if (!oldReal.equals(oldEntered)) {
                    audience.showDialog(createDialog(
                            AuthMode.CHANGE,
                            Component.text(
                                    mes("error-wrong-password", "Wrong password."),
                                    TextColor.color(220, 20, 60)
                            ),
                            uuid
                    ));
                    return;
                }

                if (new1.length() < min || new1.length() > max ||
                        new2.length() < min || new2.length() > max) {

                    audience.showDialog(createDialog(
                            AuthMode.CHANGE,
                            Component.text(
                                    mes("error-password-length",
                                            "Password must be between %min% and %max% characters."),
                                    TextColor.color(220, 20, 60)
                            ),
                            uuid
                    ));
                    return;
                }

                if (!Objects.equals(new1, new2) || new1.contains(" ") || new1.isBlank()) {
                    audience.showDialog(createDialog(
                            AuthMode.CHANGE,
                            Component.text(
                                    mes("error-password-mismatch", "Passwords do not match."),
                                    TextColor.color(220, 20, 60)
                            ),
                            uuid
                    ));
                    return;
                }

                plugin.db().saveUser(uuid, new1);

                audience.closeDialog();
                if (audience instanceof Player player) {
                    player.sendMessage(
                            ChatColor.translateAlternateColorCodes('&',
                                    plugin.getConfig().getString(
                                            "messages.password-change-success",
                                            "&aPassword changed successfully!"
                                    )
                            )
                    );
                }
            }
        }

        //

        private void setConnectionJoinResult(UUID uniqueId, String value) {
            CompletableFuture<String> future = awaitingResponse.get(uniqueId);
            if (future != null) {
                future.complete(value);
            }
        }

        //

        private String mes(String key, String alt) {
            String raw = plugin.getConfig().getString("messages." + key, alt);

            int min = plugin.getConfig().getInt("limits.min-password-length", 1);
            int max = plugin.getConfig().getInt("limits.max-password-length", 32);

            raw = ChatColor.translateAlternateColorCodes('&', raw);

            return raw.replace("%min%", String.valueOf(min))
                    .replace("%max%", String.valueOf(max));
        }
    }
}
