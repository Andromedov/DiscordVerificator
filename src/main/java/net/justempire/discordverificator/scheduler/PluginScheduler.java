package net.justempire.discordverificator.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Routes work to the scheduler that owns the affected Bukkit state.
 * Paper implements these APIs too, so one implementation supports both Paper and Folia.
 */
public final class PluginScheduler {
    private final Plugin plugin;

    public PluginScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void runAsync(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (plugin.isEnabled()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> action.run());
        }
    }

    public ScheduledTask runAsyncAtFixedRate(Runnable action, Duration initialDelay, Duration period) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(period, "period");
        return plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin,
                task -> action.run(),
                initialDelay.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    public void runGlobal(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (plugin.isEnabled()) {
            plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> action.run());
        }
    }

    public void runFor(CommandSender sender, Runnable action) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(action, "action");
        if (!plugin.isEnabled()) {
            return;
        }

        if (sender instanceof Entity entity) {
            entity.getScheduler().run(plugin, task -> action.run(), null);
        } else if (sender instanceof BlockCommandSender blockSender) {
            plugin.getServer().getRegionScheduler().run(
                    plugin,
                    blockSender.getBlock().getLocation(),
                    task -> action.run()
            );
        } else {
            runGlobal(action);
        }
    }

    public void broadcastToPlayers(RunnableForPlayer action) {
        Objects.requireNonNull(action, "action");
        runGlobal(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                player.getScheduler().run(plugin, task -> action.run(player), null);
            }
        });
    }

    @FunctionalInterface
    public interface RunnableForPlayer {
        void run(Player player);
    }
}
