package com.mrfermz.mcplugins.money.listener;

import com.mrfermz.mcplugins.money.economy.MoneyEconomyService;
import com.mrfermz.mcplugins.money.storage.MoneyStorage;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Creates an account for first-time players the moment they join, writing the
 * row to the database immediately (off the main thread) rather than waiting for
 * the next balance change / buffered flush.
 */
public final class AccountListener implements Listener {

    private final Plugin plugin;
    private final MoneyEconomyService economy;
    private final MoneyStorage storage;

    public AccountListener(Plugin plugin, MoneyEconomyService economy, MoneyStorage storage) {
        this.plugin = plugin;
        this.economy = economy;
        this.storage = storage;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        economy.seedIfAbsent(player.getUniqueId()).ifPresent(starting ->
                plugin.getServer().getAsyncScheduler().runNow(plugin,
                        task -> storage.create(player.getUniqueId(), starting)));
    }
}
