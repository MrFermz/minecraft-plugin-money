package com.mrfermz.mcplugins.money.command;

import com.mrfermz.mcplugins.core.api.EconomyResponse;
import com.mrfermz.mcplugins.money.economy.MoneyEconomyService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single root command for the whole plugin: {@code /money [subcommand] ...}.
 *
 * <ul>
 *   <li>{@code /money} — your own balance</li>
 *   <li>{@code /money help} — list subcommands</li>
 *   <li>{@code /money pay <player> <amount>}</li>
 *   <li>{@code /money balance [player]}</li>
 *   <li>{@code /money top}</li>
 *   <li>{@code /money give|take|set|reset <player> [amount]} (admin)</li>
 * </ul>
 */
public final class MoneyCommand implements TabExecutor {

    private static final int TOP_LIMIT = 10;
    private static final List<String> ADMIN_SUBS = List.of("give", "take", "set", "reset");

    private final MoneyEconomyService economy;
    private final BigDecimal startingBalance;

    public MoneyCommand(MoneyEconomyService economy, BigDecimal startingBalance) {
        this.economy = economy;
        this.startingBalance = startingBalance;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            return ownBalance(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "help", "?" -> help(sender, label);
            case "pay" -> pay(sender, label, args);
            case "balance", "bal" -> balance(sender, args);
            case "top", "baltop" -> top(sender);
            case "give", "take", "set", "reset" -> admin(sender, sub, label, args);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand '" + args[0] + "'. Try /" + label + " help");
                yield true;
            }
        };
    }

    // ----- /money (no args) and /money balance -----

    private boolean ownBalance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Console must specify a player: /money balance <player>");
            return true;
        }
        if (!sender.hasPermission("money.balance")) {
            return denied(sender, "check your balance");
        }
        sender.sendMessage(ChatColor.GOLD + "Balance: " + ChatColor.GREEN
                + economy.format(economy.getBalance(player.getUniqueId())));
        return true;
    }

    private boolean balance(CommandSender sender, String[] args) {
        if (args.length < 2) {
            return ownBalance(sender);
        }
        if (!sender.hasPermission("money.balance.others")) {
            return denied(sender, "check other players' balances");
        }
        OfflinePlayer target = sender.getServer().getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player: " + args[1]);
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + target.getName() + "'s balance: " + ChatColor.GREEN
                + economy.format(economy.getBalance(target.getUniqueId())));
        return true;
    }

    // ----- /money pay -----

    private boolean pay(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player payer)) {
            sender.sendMessage(ChatColor.RED + "Only players can pay.");
            return true;
        }
        if (!sender.hasPermission("money.pay")) {
            return denied(sender, "pay");
        }
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " pay <player> <amount>");
            return true;
        }
        Player target = sender.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not online: " + args[1]);
            return true;
        }
        if (target.getUniqueId().equals(payer.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "You can't pay yourself.");
            return true;
        }
        BigDecimal amount = Amounts.parsePositive(args[2]).orElse(null);
        if (amount == null) {
            sender.sendMessage(ChatColor.RED + "Amount must be a positive number.");
            return true;
        }

        EconomyResponse response = economy.transfer(payer.getUniqueId(), target.getUniqueId(), amount);
        if (!response.success()) {
            if (response.error() == EconomyResponse.Error.INSUFFICIENT_FUNDS) {
                sender.sendMessage(ChatColor.RED + "You can't afford that. Balance: "
                        + economy.format(economy.getBalance(payer.getUniqueId())));
            } else {
                sender.sendMessage(ChatColor.RED + "Payment failed: " + response.error());
            }
            return true;
        }
        String formatted = economy.format(response.amount());
        sender.sendMessage(ChatColor.GREEN + "Paid " + formatted + " to " + target.getName()
                + ChatColor.GRAY + " (balance: " + economy.format(response.newBalance()) + ")");
        target.sendMessage(ChatColor.GREEN + "Received " + formatted + " from " + payer.getName());
        return true;
    }

    // ----- /money top -----

    private boolean top(CommandSender sender) {
        if (!sender.hasPermission("money.baltop")) {
            return denied(sender, "view the balance top");
        }
        List<Map.Entry<UUID, BigDecimal>> top = economy.snapshot().entrySet().stream()
                .sorted(Map.Entry.<UUID, BigDecimal>comparingByValue().reversed())
                .limit(TOP_LIMIT)
                .toList();
        if (top.isEmpty()) {
            sender.sendMessage(ChatColor.GOLD + "No balances recorded yet.");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "--- Top " + top.size() + " balances ---");
        int rank = 1;
        for (Map.Entry<UUID, BigDecimal> entry : top) {
            String name = sender.getServer().getOfflinePlayer(entry.getKey()).getName();
            sender.sendMessage(ChatColor.YELLOW + "" + rank + ". " + ChatColor.WHITE
                    + (name != null ? name : entry.getKey().toString())
                    + ChatColor.GRAY + " — " + ChatColor.GREEN + economy.format(entry.getValue()));
            rank++;
        }
        return true;
    }

    // ----- /money give|take|set|reset -----

    private boolean admin(CommandSender sender, String sub, String label, String[] args) {
        if (!sender.hasPermission("money.admin." + sub) && !sender.hasPermission("money.admin")) {
            return denied(sender, sub + " money");
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " " + sub + " <player>"
                    + (sub.equals("reset") ? "" : " <amount>"));
            return true;
        }
        OfflinePlayer target = sender.getServer().getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Unknown player: " + args[1]
                    + ChatColor.GRAY + " (must have joined before).");
            return true;
        }

        if (sub.equals("reset")) {
            EconomyResponse r = economy.setBalance(target.getUniqueId(), startingBalance);
            sender.sendMessage(ChatColor.GREEN + "Reset " + target.getName() + " to "
                    + economy.format(r.newBalance()));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " " + sub + " <player> <amount>");
            return true;
        }
        BigDecimal amount = (sub.equals("set")
                ? Amounts.parseNonNegative(args[2])
                : Amounts.parsePositive(args[2])).orElse(null);
        if (amount == null) {
            sender.sendMessage(ChatColor.RED + "Amount must be a "
                    + (sub.equals("set") ? "non-negative" : "positive") + " number.");
            return true;
        }

        EconomyResponse response = switch (sub) {
            case "give" -> economy.deposit(target.getUniqueId(), amount);
            case "take" -> economy.withdraw(target.getUniqueId(), amount);
            case "set" -> economy.setBalance(target.getUniqueId(), amount);
            default -> throw new IllegalStateException(sub);
        };
        if (!response.success()) {
            if (response.error() == EconomyResponse.Error.INSUFFICIENT_FUNDS) {
                sender.sendMessage(ChatColor.RED + target.getName() + " only has "
                        + economy.format(response.newBalance()) + " to take.");
            } else {
                sender.sendMessage(ChatColor.RED + "Operation failed: " + response.error());
            }
            return true;
        }
        String msg = switch (sub) {
            case "give" -> "Gave " + economy.format(response.amount()) + " to " + target.getName();
            case "take" -> "Took " + economy.format(response.amount()) + " from " + target.getName();
            default -> "Set " + target.getName() + " to " + economy.format(response.newBalance());
        };
        sender.sendMessage(ChatColor.GREEN + msg
                + ChatColor.GRAY + " (balance: " + economy.format(response.newBalance()) + ")");
        return true;
    }

    // ----- /money help -----

    private boolean help(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "--- /" + label + " ---");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + ChatColor.GRAY + " — your balance");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " balance [player]" + ChatColor.GRAY + " — view a balance");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " pay <player> <amount>" + ChatColor.GRAY + " — send money");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " top" + ChatColor.GRAY + " — richest players");
        if (sender.hasPermission("money.admin") || sender.hasPermission("money.admin.give")) {
            sender.sendMessage(ChatColor.RED + "/" + label + " give|take|set <player> <amount>"
                    + ChatColor.GRAY + " — admin");
            sender.sendMessage(ChatColor.RED + "/" + label + " reset <player>"
                    + ChatColor.GRAY + " — admin: reset to starting balance");
        }
        return true;
    }

    private boolean denied(CommandSender sender, String action) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to " + action + ".");
        return true;
    }

    // ----- tab completion -----

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("help", "balance", "pay", "top"));
            if (sender.hasPermission("money.admin") || sender.hasPermission("money.admin.give")) {
                subs.addAll(ADMIN_SUBS);
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("pay") || sub.equals("balance") || sub.equals("bal") || ADMIN_SUBS.contains(sub)) {
                List<String> names = new ArrayList<>();
                sender.getServer().getOnlinePlayers().forEach(p -> names.add(p.getName()));
                return filter(names, args[1]);
            }
            return List.of();
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("pay") || sub.equals("give") || sub.equals("take") || sub.equals("set")) {
                return filter(List.of("1", "10", "100", "1000"), args[2]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
