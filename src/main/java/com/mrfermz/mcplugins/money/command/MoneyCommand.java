package com.mrfermz.mcplugins.money.command;

import com.mrfermz.mcplugins.core.api.EconomyResponse;
import com.mrfermz.mcplugins.money.economy.MoneyEconomyService;
import com.mrfermz.mcplugins.money.transaction.Transaction;
import com.mrfermz.mcplugins.money.transaction.TransactionLog;
import com.mrfermz.mcplugins.money.transaction.TransactionType;
import com.mrfermz.mcplugins.money.transaction.TxMeta;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
import org.bukkit.plugin.Plugin;
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
    private static final int LOG_LIMIT = 10;
    private static final int LOG_MAX = 50;
    private static final List<String> ADMIN_SUBS = List.of("give", "take", "set", "reset", "log");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final MoneyEconomyService economy;
    private final TransactionLog txLog;
    private final BigDecimal startingBalance;

    public MoneyCommand(Plugin plugin, MoneyEconomyService economy, TransactionLog txLog,
                        BigDecimal startingBalance) {
        this.plugin = plugin;
        this.economy = economy;
        this.txLog = txLog;
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
            case "log", "logs", "history" -> log(sender, args);
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

        EconomyResponse response = economy.transfer(payer.getUniqueId(), target.getUniqueId(), amount,
                TxMeta.by(TransactionType.PAY, payer.getUniqueId()));
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

        UUID actor = sender instanceof Player p ? p.getUniqueId() : null;

        if (sub.equals("reset")) {
            EconomyResponse r = economy.setBalance(target.getUniqueId(), startingBalance,
                    TxMeta.by(TransactionType.RESET, actor));
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
            case "give" -> economy.deposit(target.getUniqueId(), amount, TxMeta.by(TransactionType.GIVE, actor));
            case "take" -> economy.withdraw(target.getUniqueId(), amount, TxMeta.by(TransactionType.TAKE, actor));
            case "set" -> economy.setBalance(target.getUniqueId(), amount, TxMeta.by(TransactionType.SET, actor));
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

    // ----- /money log -----

    private boolean log(CommandSender sender, String[] args) {
        if (!sender.hasPermission("money.admin.log") && !sender.hasPermission("money.admin")) {
            return denied(sender, "view the transaction log");
        }
        // /money log [player] [limit]  — either arg is optional; a bare number is
        // treated as the limit, otherwise the first extra arg is a player filter.
        UUID filter = null;
        String filterName = null;
        int limit = LOG_LIMIT;
        int next = 1;
        if (args.length > next && !isInteger(args[next])) {
            OfflinePlayer target = sender.getServer().getOfflinePlayerIfCached(args[next]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Unknown player: " + args[next]);
                return true;
            }
            filter = target.getUniqueId();
            filterName = target.getName();
            next++;
        }
        if (args.length > next && isInteger(args[next])) {
            limit = Math.min(LOG_MAX, Math.max(1, Integer.parseInt(args[next])));
        }

        UUID queryFilter = filter;
        int queryLimit = limit;
        String heading = ChatColor.GOLD + "--- Last " + limit + " transactions"
                + (filterName != null ? " for " + filterName : "") + " ---";
        // Hit the DB off the main thread, then resolve names and report back.
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            List<Transaction> rows = txLog.recent(queryFilter, queryLimit);
            List<String> lines = new ArrayList<>();
            lines.add(heading);
            if (rows.isEmpty()) {
                lines.add(ChatColor.GRAY + "No transactions recorded yet.");
            } else {
                for (Transaction tx : rows) {
                    lines.add(render(sender, tx));
                }
            }
            lines.forEach(sender::sendMessage);
        });
        return true;
    }

    /** Formats one transaction row for chat, resolving UUIDs to names. */
    private String render(CommandSender sender, Transaction tx) {
        String when = ChatColor.DARK_GRAY + TIME.format(Instant.ofEpochMilli(tx.timestamp()));
        String amount = ChatColor.GREEN + economy.format(tx.amount());
        String from = name(sender, tx.from());
        String to = name(sender, tx.to());
        String body = switch (tx.type()) {
            case PAY, TRANSFER -> ChatColor.WHITE + from + ChatColor.GRAY + " → "
                    + ChatColor.WHITE + to + ChatColor.GRAY + ": " + amount;
            case GIVE -> ChatColor.GRAY + "gave " + ChatColor.WHITE + to + ChatColor.GRAY + ": " + amount
                    + byActor(sender, tx);
            case TAKE -> ChatColor.GRAY + "took from " + ChatColor.WHITE + from + ChatColor.GRAY + ": " + amount
                    + byActor(sender, tx);
            case SET -> ChatColor.GRAY + "set " + ChatColor.WHITE + to + ChatColor.GRAY + " = " + amount
                    + byActor(sender, tx);
            case RESET -> ChatColor.GRAY + "reset " + ChatColor.WHITE + to + ChatColor.GRAY + " = " + amount
                    + byActor(sender, tx);
            case DEPOSIT -> ChatColor.GRAY + "deposit " + ChatColor.WHITE + to + ChatColor.GRAY + ": " + amount;
            case WITHDRAW -> ChatColor.GRAY + "withdraw " + ChatColor.WHITE + from + ChatColor.GRAY + ": " + amount;
        };
        return when + " " + ChatColor.YELLOW + tx.type() + " " + body;
    }

    private String byActor(CommandSender sender, Transaction tx) {
        if (tx.actor() == null) {
            return ChatColor.DARK_GRAY + " (by console)";
        }
        return ChatColor.DARK_GRAY + " (by " + name(sender, tx.actor()) + ")";
    }

    private String name(CommandSender sender, UUID id) {
        if (id == null) {
            return "-";
        }
        String n = sender.getServer().getOfflinePlayer(id).getName();
        return n != null ? n : id.toString().substring(0, 8);
    }

    private static boolean isInteger(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
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
            sender.sendMessage(ChatColor.RED + "/" + label + " log [player] [limit]"
                    + ChatColor.GRAY + " — admin: recent transactions");
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
