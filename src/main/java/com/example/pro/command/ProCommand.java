package com.example.pro.command;

import com.example.pro.JavaPluginsPro;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Bukkit adapter for the {@code /plugin} command:
 * <pre>
 *   /plugin status                 show version, state, dirs and resource counters
 *   /plugin cleanup [--dry-run]    delete plugin-created temp files/backups (registered only)
 *   /plugin threads                list every plugin-created thread
 * </pre>
 * This class only translates between Bukkit and the plugin services; all logic lives in
 * {@code SafeCleaner} / {@code ThreadManager} / {@code PluginStatus} so it stays unit-testable.
 */
public final class ProCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of("status", "cleanup", "threads");
    private static final String USAGE = "Usage: /plugin <status|cleanup [--dry-run]|threads>";

    private final JavaPluginsPro plugin;

    public ProCommand(JavaPluginsPro plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> plugin.statusLines().forEach(sender::sendMessage);
            case "cleanup" -> {
                boolean dryRun = Arrays.stream(args).skip(1)
                        .anyMatch(arg -> arg.equalsIgnoreCase("--dry-run") || arg.equalsIgnoreCase("dry-run"));
                plugin.cleanupReportLines(dryRun).forEach(sender::sendMessage);
            }
            case "threads" -> {
                List<String> lines = plugin.threadReportLines();
                sender.sendMessage("Plugin threads (" + lines.size() + " active):");
                lines.forEach(sender::sendMessage);
            }
            default -> sender.sendMessage("Unknown subcommand. " + USAGE);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> hits = new ArrayList<>();
            for (String sub : SUB_COMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    hits.add(sub);
                }
            }
            return hits;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cleanup")) {
            return List.of("--dry-run");
        }
        return List.of();
    }
}
