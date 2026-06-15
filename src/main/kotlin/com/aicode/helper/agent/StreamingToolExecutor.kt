package com.aicode.helper.agent

import com.aicode.helper.agent.hooks.HookManager
import com.aicode.helper.agent.hooks.PermissionDecision
import com.aicode.helper.agent.tools.ToolExecutionContext
import com.aicode.helper.agent.tools.ToolRegistry
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/** 工具执行结果。 */
data class ToolExecResult(
    val toolCallId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean
)

/**
 * 决策 #4：流式并发执行——工具不用等模型说完就开始跑。
 *
 * - 后台调度协程在创建时就启动，模型还在流式输出时，已就绪的 tool_use 块经 submit()
 *   立刻入队并被调度执行。
 * - 调度规则：只有当所有正在执行的工具都并发安全时，才能启动新的并发安全工具；
 *   非安全工具必须独占执行。
 * - siblingAbortController：某个并行工具报错时，同批次其它工具立即收到取消信号。
 *
 * 每个 assistant 响应对应一个“批次”，因此每轮迭代新建一个 StreamingToolExecutor。
 */
class StreamingToolExecutor(
    parentJob: Job?,
    private val registry: ToolRegistry,
    private val hooks: HookManager,
    private val ctx: ToolExecutionContext
) {
    private val log = Logger.getInstance(StreamingToolExecutor::class.java)

    // SupervisorJob：单个工具失败不会自动连坐取消整个 scope（我们手动做 sibling abort）
    private val batchScope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)

    private val queue = Channel<ToolCall>(Channel.UNLIMITED)
    private val results = Collections.synchronizedList(ArrayList<ToolExecResult>())
    private val active = CopyOnWriteArrayList<Deferred<ToolExecResult>>()

    @Volatile
    private var aborted = false

    private val scheduler: Job = batchScope.launch { schedule() }

    /** 模型流式输出期间，把已就绪的工具调用入队。 */
    fun submit(call: ToolCall) {
        queue.trySend(call)
    }

    /** 关闭入队、等待全部工具完成，返回结果（按完成顺序）。 */
    suspend fun complete(): List<ToolExecResult> {
        queue.close()
        scheduler.join()
        batchScope.cancel()
        return results.toList()
    }

    /** 上下文压缩重试等场景：丢弃本批次。 */
    fun cancelAll() {
        queue.close()
        batchScope.cancel()
    }

    private suspend fun schedule() {
        val running = ArrayList<Deferred<ToolExecResult>>()
        for (call in queue) {
            val tool = registry.find(call.name)
            val safe = tool != null &&
                    runCatching { tool.isConcurrencySafe(call.argumentsJson) }.getOrDefault(false)

            if (safe) {
                // 并发安全：可与其它安全工具并行（此处不会有正在运行的非安全工具，
                // 因为非安全工具是独占 await 的）
                running.add(launchTool(call))
            } else {
                // 非安全/未知工具：必须独占执行——先等所有正在运行的安全工具完成
                drainInto(running)
                if (tool == null) {
                    results.add(ToolExecResult(call.id, call.name, "未知工具: ${call.name}", true))
                } else {
                    results.add(awaitOne(launchTool(call)))
                }
            }
        }
        drainInto(running)
    }

    private fun launchTool(call: ToolCall): Deferred<ToolExecResult> {
        val d = batchScope.async { runSingle(call) }
        active.add(d)
        return d
    }

    private suspend fun runSingle(call: ToolCall): ToolExecResult {
        if (aborted) return ToolExecResult(call.id, call.name, "已取消（同批次工具失败）", true)

        val tool = registry.find(call.name)
        // PreToolUse hook —— 权限检查
        when (val decision = hooks.firePreToolUse(call, tool, ctx)) {
            is PermissionDecision.Deny ->
                return ToolExecResult(call.id, call.name, "权限拒绝: ${decision.reason}", true)
            else -> { /* Allow / Ask：无交互环境下放行（策略已在 PermissionHook 决定） */ }
        }

        return try {
            val out = tool!!.execute(call.argumentsJson, ctx)
            hooks.firePostToolUse(call, out, false, ctx)
            ToolExecResult(call.id, call.name, out, false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 决策 #4：sibling abort —— 立即取消同批次其它工具
            aborted = true
            active.forEach { it.cancel() }
            val msg = "执行失败: ${e.message}"
            hooks.firePostToolUse(call, msg, true, ctx)
            ToolExecResult(call.id, call.name, msg, true)
        }
    }

    private suspend fun drainInto(running: MutableList<Deferred<ToolExecResult>>) {
        for (d in running) results.add(awaitOne(d))
        running.clear()
    }

    private suspend fun awaitOne(d: Deferred<ToolExecResult>): ToolExecResult = try {
        d.await()
    } catch (ce: CancellationException) {
        ToolExecResult("", "?", "已取消（同批次工具失败）", true)
    } catch (e: Exception) {
        log.warn("工具等待异常: ${e.message}")
        ToolExecResult("", "?", "执行异常: ${e.message}", true)
    }
}

