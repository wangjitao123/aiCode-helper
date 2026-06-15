package com.aicode.helper.agent.context

/**
 * 决策 #3 / #6：五层压缩策略所用的常量，以及运营数据驱动的工程决策。
 *
 * 这些数字不是凭空估算——每个都附带它的来历注释，
 * 这样下一个改代码的人就知道改动会有什么后果（决策 #6）。
 *
 * 对应源码：
 *   // 2026-03-10: 1,279 sessions had 50+ consecutive failures (up to 3,272)
 *   // in a single session, wasting ~250K API calls/day globally.
 *   // Based on p99.99 of compact summary output being 17,387 tokens.
 */
object CompactionConstants {

    /**
     * 主动压缩（autocompact）的安全余量。
     * 当 估算 token 数 > contextWindow - AUTOCOMPACT_BUFFER_TOKENS 时触发 L3。
     */
    const val AUTOCOMPACT_BUFFER_TOKENS = 13_000

    /**
     * 压缩摘要的输出 token 预算。
     * 实测压缩摘要输出的 p99.99 为 17,387 tokens，因此预算取 20,000，留出冗余。
     */
    const val MAX_OUTPUT_TOKENS_FOR_SUMMARY = 20_000

    /**
     * L2 snip 触发阈值：当消息条数超过该值时，截断中间段落。
     * 经验值：超过 ~60 条后，中间的探索性消息对最终答案贡献很低。
     */
    const val SNIP_MESSAGE_THRESHOLD = 60

    /** snip 时保留最近的消息条数（不含 system 与首条 user）。 */
    const val SNIP_KEEP_RECENT = 20

    /**
     * L1 microcompact：单条工具结果超过该字符数即视为“大结果”，
     * 历史中较早的重复/超大工具结果会被折叠。
     */
    const val MICROCOMPACT_LARGE_RESULT_CHARS = 2_000

    /** microcompact 折叠较早工具结果时保留的字符数。 */
    const val MICROCOMPACT_FOLD_KEEP_CHARS = 300

    /** 最近 N 条工具结果不参与 microcompact 折叠（它们通常仍然相关）。 */
    const val MICROCOMPACT_RECENT_KEPT = 3
}

