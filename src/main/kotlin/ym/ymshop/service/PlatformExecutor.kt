package ym.ymshop.service

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

class PlatformExecutor(private val plugin: JavaPlugin) {

    private val globalSchedulerMethod = runCatching { plugin.server.javaClass.getMethod("getGlobalRegionScheduler") }.getOrNull()
    private val isFolia = globalSchedulerMethod != null
    private val asyncExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "Ymshop-Async-Worker").apply { isDaemon = true }
    }

    fun runGlobal(task: () -> Unit) {
        if (!isFolia) {
            if (Bukkit.isPrimaryThread()) {
                task()
                return
            }
            plugin.server.scheduler.runTask(plugin, Runnable(task))
            return
        }

        val scheduler = requireNotNull(ReflectionSupport.invoke(globalSchedulerMethod!!, plugin.server))
        val executeMethod = scheduler.javaClass.getMethod("execute", Plugin::class.java, Runnable::class.java)
        ReflectionSupport.invoke(executeMethod, scheduler, plugin, Runnable(task))
    }

    fun runGlobalLater(delayTicks: Long, task: () -> Unit): TaskHandle {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        if (!isFolia) {
            val bukkitTask = plugin.server.scheduler.runTaskLater(plugin, Runnable(task), safeDelay)
            return BukkitTaskHandle(bukkitTask)
        }

        val scheduler = requireNotNull(ReflectionSupport.invoke(globalSchedulerMethod!!, plugin.server))
        val runDelayedMethod = scheduler.javaClass.methods.firstOrNull { method ->
            method.name == "runDelayed" &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == Plugin::class.java &&
                Consumer::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                method.parameterTypes[2] == java.lang.Long.TYPE
        } ?: error("Folia global scheduler does not expose runDelayed")

        val scheduledTask = ReflectionSupport.invoke(runDelayedMethod, scheduler, plugin, Consumer<Any> { task() }, safeDelay)
        return ReflectiveTaskHandle(scheduledTask)
    }

    fun runGlobalTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val safePeriod = periodTicks.coerceAtLeast(1L)
        if (!isFolia) {
            val bukkitTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable(task), safeDelay, safePeriod)
            return BukkitTaskHandle(bukkitTask)
        }

        val scheduler = requireNotNull(ReflectionSupport.invoke(globalSchedulerMethod!!, plugin.server))
        val runAtFixedRateMethod = scheduler.javaClass.methods.firstOrNull { method ->
            method.name == "runAtFixedRate" &&
                method.parameterCount == 4 &&
                method.parameterTypes[0] == Plugin::class.java &&
                Consumer::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                method.parameterTypes[2] == java.lang.Long.TYPE &&
                method.parameterTypes[3] == java.lang.Long.TYPE
        } ?: error("Folia global scheduler does not expose runAtFixedRate")

        val scheduledTask = ReflectionSupport.invoke(
            runAtFixedRateMethod,
            scheduler,
            plugin,
            Consumer<Any> { task() },
            safeDelay,
            safePeriod
        )
        return ReflectiveTaskHandle(scheduledTask)
    }

    fun runGlobalAsync(task: () -> Unit): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync({
            task()
            Unit
        }, asyncExecutor)
    }

    fun runForSender(sender: CommandSender, task: () -> Unit): Boolean {
        val player = sender as? Player
        return if (player != null) {
            runForPlayer(player, task)
        } else {
            runGlobal(task)
            true
        }
    }

    fun runForPlayer(player: Player, task: () -> Unit): Boolean {
        if (!isFolia) {
            if (Bukkit.isPrimaryThread()) {
                task()
                return true
            }
            plugin.server.scheduler.runTask(plugin, Runnable(task))
            return true
        }

        val scheduler = requireNotNull(ReflectionSupport.invoke(player.javaClass.getMethod("getScheduler"), player))
        val executeMethod = scheduler.javaClass.getMethod(
            "execute",
            Plugin::class.java,
            Runnable::class.java,
            Runnable::class.java,
            java.lang.Long.TYPE
        )
        return ReflectionSupport.invoke(executeMethod, scheduler, plugin, Runnable(task), null, 1L) as Boolean
    }

    fun runForPlayerLater(player: Player, delayTicks: Long, task: () -> Unit): Boolean {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        if (!isFolia) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable(task), safeDelay)
            return true
        }

        val scheduler = requireNotNull(ReflectionSupport.invoke(player.javaClass.getMethod("getScheduler"), player))
        val runDelayedMethod = scheduler.javaClass.methods.firstOrNull { method ->
            method.name == "runDelayed" &&
                method.parameterCount == 4 &&
                method.parameterTypes[0] == Plugin::class.java &&
                Consumer::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                method.parameterTypes[2] == Runnable::class.java &&
                method.parameterTypes[3] == java.lang.Long.TYPE
        } ?: error("Folia player scheduler does not expose runDelayed")

        val scheduledTask = ReflectionSupport.invoke(
            runDelayedMethod,
            scheduler,
            plugin,
            Consumer<Any> { task() },
            null,
            safeDelay
        )
        return scheduledTask != null
    }

    fun close() {
        asyncExecutor.shutdown()
    }

    interface TaskHandle {
        fun cancel()
    }

    private class BukkitTaskHandle(private val task: BukkitTask) : TaskHandle {
        override fun cancel() {
            task.cancel()
        }
    }

    private class ReflectiveTaskHandle(private val task: Any?) : TaskHandle {
        override fun cancel() {
            val cancelMethod = task?.javaClass?.methods?.firstOrNull { it.name == "cancel" && it.parameterCount == 0 } ?: return
            ReflectionSupport.invoke(cancelMethod, task)
        }
    }
}
