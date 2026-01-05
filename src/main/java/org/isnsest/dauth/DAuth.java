package org.isnsest.dauth;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
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
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DAuth extends JavaPlugin {

    private Database database;
    public final Map<UUID, String> pendingSetupSecrets = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        database = new Database(this);
        Bukkit.getPluginManager().registerEvents(new EventListener(this), this);

        getCommand("logout").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                PlayerGameConnection connection = player.getConnection();
                String ip = Arrays.stream(connection.getAddress().toString().split(":")).findFirst().orElse("null");
                LogoutTimerManager.removeSession(player.getUniqueId());
                player.kick(Component.text(mes("logout", "Logout")));
            }
            return true;
        });

        getCommand("2fa").setExecutor(new Command2FA(this));

        getCommand("unregister").setExecutor((sender, command, label, args) -> {
            if (args.length > 0) {
                if (!sender.hasPermission("dauth.admin.unregister")) {
                    sender.sendMessage(ChatColor.RED + "No permission.");
                    return true;
                }
                String targetName = args[0];
                Player player = Bukkit.getPlayer(targetName);

                UUID targetUUID = Bukkit.getOfflinePlayer(targetName).getUniqueId();

                database.deleteUser(targetUUID);

                sender.sendMessage(ChatColor.GREEN + "Player " + targetName + " unregistered.");

                if (player != null) player.kick(Component.text("Account deleted. Please rejoin."));
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Console usage: /unregister <player>");
                return true;
            }

            UUID uuid = player.getUniqueId();

            if (!database.isRegistered(uuid)) {
                player.sendMessage(ChatColor.RED + "You are not registered.");
                return true;
            }

            database.deleteUser(uuid);

            player.sendMessage(ChatColor.GREEN + "You have been unregistered.");
            player.kick(Component.text("Account deleted. Please rejoin."));

            return true;
        });


        getCommand("dreload").setExecutor((sender, command, label, args) -> {
            sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
            reloadConfig();
            return true;
        });

        getCommand("changepassword").setExecutor((sender, cmd, lbl, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players.");
                return true;
            }
            UUID uuid = player.getUniqueId();
            if (!database.isRegistered(uuid)) {
                player.sendMessage(ChatColor.RED + "You are not registered.");
                return true;
            }

            player.showDialog(EventListener.createDialogStatic(this, EventListener.AuthMode.CHANGE,
                    Component.text(mes("title-change", "Change Password")), uuid));
            return true;
        });
    }

    @Override
    public void onDisable() {}

    public Database db() {
        return database;
    }

    public String mes(String key, String def) {
        String raw = getConfig().getString("messages." + key, def);
        int min = getConfig().getInt("limits.min-password-length", 3);
        int max = getConfig().getInt("limits.max-password-length", 32);

        raw = ChatColor.translateAlternateColorCodes('&', raw);
        return raw.replace("%min%", String.valueOf(min))
                .replace("%max%", String.valueOf(max));
    }

    public Component component(String key, String def) {
        String text = getConfig().getString("messages." + key, def);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    public List<String> list(String key) {
        List<String> list = getConfig().getStringList("messages." + key);
        if (list.isEmpty()) return List.of("Missing list: " + key);
        return list.stream()
                .map(s -> ChatColor.translateAlternateColorCodes('&', s))
                .toList();
    }


    public static class EventListener implements Listener {
        private final DAuth plugin;
        private final GoogleAuthenticator gAuth = new GoogleAuthenticator();
        private final Map<UUID, Integer> wrongs = new HashMap<>();
        private final Map<UUID, CompletableFuture<String>> awaitingResponse = new ConcurrentHashMap<>();
        private static final ClickCallback.Options options = ClickCallback.Options.builder().uses(1).lifetime(ClickCallback.DEFAULT_LIFETIME).build();

        public enum AuthMode { LOGIN, REGISTER, CHANGE, VERIFY_2FA }

        public EventListener(DAuth plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onPlayerConfigure(AsyncPlayerConnectionConfigureEvent event) {
            PlayerConfigurationConnection connection = event.getConnection();
            String ip = Arrays.stream(connection.getAddress().toString().split(":")).findFirst().orElse("null");

            UUID id = connection.getProfile().getId();
            if (id == null) return;
            if (LogoutTimerManager.checkSession(id, ip) && plugin.db().isRegistered(id)) {
                LogoutTimerManager.cancelTimer(id);
                return;
            }

            AuthMode mode = !plugin.db().isRegistered(id) ? AuthMode.REGISTER : AuthMode.LOGIN;

            Dialog dialog = createDialog(mode, Component.text(plugin.mes("title", "Auth required")), id);

            CompletableFuture<String> response = new CompletableFuture<>();
            int timeout = plugin.getConfig().getInt("limits.timeout-seconds", 60);
            response.completeOnTimeout("Timeout", timeout, TimeUnit.SECONDS);

            awaitingResponse.put(id, response);
            connection.getAudience().showDialog(dialog);

            String res = response.join();

            awaitingResponse.remove(id);
            wrongs.remove(id);

            switch (res) {
                case "Timeout" -> { connection.getAudience().closeDialog(); connection.disconnect(Component.text(plugin.mes("error-timeout", "Authentication timeout."))); }
                case "Wrong password" -> { connection.getAudience().closeDialog(); connection.disconnect(Component.text(plugin.mes("error-wrong-password", "Wrong password."))); }
                case "Exit" -> { connection.getAudience().closeDialog(); connection.disconnect(Component.text(plugin.mes("quit", "You have left the server."))); }
                case "Done" -> { connection.getAudience().closeDialog(); LogoutTimerManager.updateSession(id, ip); }
                default -> { connection.getAudience().closeDialog(); connection.disconnect(Component.text(plugin.mes("error-generic", res))); }
            }
        }

        @EventHandler
        public void onLogin(PlayerLoginEvent event) {
            if (Bukkit.getOnlinePlayers().contains(event.getPlayer())) {
                event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "This account is already on the server.");
            }
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            PlayerGameConnection connection = event.getPlayer().getConnection();
            String ip = Arrays.stream(connection.getAddress().toString().split(":")).findFirst().orElse("null");
            if (LogoutTimerManager.checkSession(playerId, ip)) {
                LogoutTimerManager.startTimer(playerId, () -> LogoutTimerManager.removeSession(playerId), plugin.getConfig().getInt("limits.session", 300));
            }
        }

        @EventHandler
        void onClose(PlayerConnectionCloseEvent e) {
            awaitingResponse.remove(e.getPlayerUniqueId());
            wrongs.remove(e.getPlayerUniqueId());
            plugin.pendingSetupSecrets.remove(e.getPlayerUniqueId());
        }


        public static Dialog createDialogStatic(DAuth plugin, AuthMode mode, Component title, UUID uuid) {
            return new EventListener(plugin).createDialog(mode, title, uuid);
        }

        private Dialog createDialog(AuthMode mode, Component title, UUID uuid) {
            int max = plugin.getConfig().getInt("limits.max-password-length", 32);
            List<DialogInput> inputs = new ArrayList<>();
            boolean canCloseWithEscape = (mode == AuthMode.CHANGE);

            switch (mode) {
                case LOGIN -> inputs = List.of(
                        DialogInput.text("password1", 150, Component.text(plugin.mes("password-label", "Password")), true, "", max, null)
                );
                case REGISTER -> inputs = List.of(
                        DialogInput.text("password1", 150, Component.text(plugin.mes("password-label", "Password")), true, "", max, null),
                        DialogInput.text("password2", 150, Component.text(plugin.mes("password-again-label", "Repeat")), true, "", max, null)
                );
                case VERIFY_2FA -> inputs = List.of(
                        DialogInput.text("code", 150, Component.text("2FA Code"), false, "", 6, null)
                );
                case CHANGE -> inputs = List.of(
                        DialogInput.text("old", 150, Component.text(plugin.mes("old-password-label", "Old password")), true, "", max, null),
                        DialogInput.text("new1", 150, Component.text(plugin.mes("new-password-label", "New password")), true, "", max, null),
                        DialogInput.text("new2", 150, Component.text(plugin.mes("password-again-label", "Repeat password")), true, "", max, null)
                );
            }

            String btn = switch(mode) {
                case LOGIN -> plugin.mes("button-login", "Login");
                case REGISTER -> plugin.mes("button-register", "Register");
                case VERIFY_2FA -> "Verify";
                case CHANGE -> plugin.mes("button-change", "Change");
            };

            List<DialogInput> finalInputs = inputs;
            List<ActionButton> actionButtons = new ArrayList<>();
            actionButtons.add(ActionButton.create(Component.text(btn), null, 125,
                    DialogAction.customClick((v, a) -> auth(v, a, uuid, mode), options)));
            String discordLink = plugin.getConfig().getString("links.discord", "<link>");
            if (!discordLink.equalsIgnoreCase("<link>")) {
                actionButtons.add(ActionButton.create(Component.text(plugin.mes("button-discord", "Discord")), null, 60,
                        DialogAction.staticAction(ClickEvent.openUrl(discordLink))));
            }
            return Dialog.create(b -> b.empty().base(DialogBase.builder(Component.text("DAuth"))
                            .inputs(finalInputs)
                            .body(List.of(DialogBody.plainMessage(title)))
                            .canCloseWithEscape(canCloseWithEscape)
                            .build())
                    .type(DialogType.multiAction(actionButtons)
                            .exitAction(ActionButton.create(Component.text(plugin.mes("button-quit", "Quit")), null, 50,
                                    DialogAction.customClick((v, a) -> setRes(uuid, "Exit"), options)))
                            .build()));
        }

        private void auth(DialogResponseView v, Audience a, UUID uuid, AuthMode mode) {
            int min = plugin.getConfig().getInt("limits.min-password-length", 3);
            int max = plugin.getConfig().getInt("limits.max-password-length", 32);


            if (mode == AuthMode.REGISTER || mode == AuthMode.CHANGE) {

                String p1 = v.getText(mode == AuthMode.REGISTER ? "password1" : "new1");
                String p2 = v.getText(mode == AuthMode.REGISTER ? "password2" : "new2");

                if (p1.length() < min || p1.length() > max) {
                    a.showDialog(createDialog(mode, Component.text(plugin.mes("error-password-length", "Length error")).color(TextColor.color(220, 20, 60)), uuid));
                    return;
                } else if (p2 != null) {
                    if (p2.length() < min || p2.length() > max) {
                        a.showDialog(createDialog(mode, Component.text(plugin.mes("error-password-length", "Length error")).color(TextColor.color(220, 20, 60)), uuid));
                        return;
                    }
                }

                if (!p1.equals(p2) || p1.contains(" ") || p1.isBlank()) {
                    a.showDialog(createDialog(mode, Component.text(plugin.mes("error-password-mismatch", "Mismatch/Space error")).color(TextColor.color(220, 20, 60)), uuid));
                    return;
                }

                if (mode == AuthMode.CHANGE && !plugin.db().checkPassword(uuid, v.getText("old"))) {
                    a.showDialog(createDialog(mode, Component.text(plugin.mes("error-wrong-password", "Wrong old")).color(TextColor.color(220, 20, 60)), uuid));
                    return;
                }

                plugin.db().saveUser(uuid, p1);
                if (mode == AuthMode.CHANGE) {
                    a.closeDialog();
                    ((Player)a).sendMessage(plugin.mes("password-change-success", "Changed!"));
                } else {
                    setRes(uuid, "Done");
                }
                return;
            }


            if (mode == AuthMode.LOGIN) {
                if (!plugin.db().checkPassword(uuid, v.getText("password1"))) {
                    handleWrong(a, uuid, mode);
                    return;
                }

                if (plugin.db().get2FASecret(uuid) != null) {
                    wrongs.put(uuid, 0);
                    a.showDialog(createDialog(AuthMode.VERIFY_2FA, Component.text("Enter 2FA Code").color(NamedTextColor.AQUA), uuid));
                } else {
                    setRes(uuid, "Done");
                }
                return;
            }


            if (mode == AuthMode.VERIFY_2FA) {
                try {
                    String codeStr = v.getText("code").trim();
                    if (gAuth.authorize(plugin.db().get2FASecret(uuid), Integer.parseInt(codeStr))) {
                        setRes(uuid, "Done");
                    } else {
                        handleWrong(a, uuid, mode);
                    }
                } catch (Exception e) { handleWrong(a, uuid, mode); }
            }
        }

        private void handleWrong(Audience a, UUID uuid, AuthMode mode) {
            wrongs.merge(uuid, 1, Integer::sum);
            if (wrongs.get(uuid) >= 3) {
                setRes(uuid, "Wrong password");
            } else {
                a.showDialog(createDialog(mode, Component.text(plugin.mes("error-wrong-password", "Wrong!")).color(TextColor.color(220, 20, 60)), uuid));
            }
        }

        private void setRes(UUID id, String val) {
            if (awaitingResponse.containsKey(id)) awaitingResponse.get(id).complete(val);
        }
    }
}