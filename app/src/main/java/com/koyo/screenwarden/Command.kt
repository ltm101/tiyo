package com.koyo.screenwarden

/**
 * 邮件指令类型。
 */
sealed class Command {
    // ── 查询类 ──────────────────────────────────────────
    /** 查询屏幕使用报告 */
    data object Report : Command()

    /** 查询文件：path 是要列出的目录或要读取的文件 */
    data class FileQuery(val path: String) : Command()

    // ── 动作类 ──────────────────────────────────────────
    /** 响铃找手机 */
    data object Ring : Command()

    /** 推送一条通知到手机，text 是通知内容 */
    data class Notify(val text: String) : Command()

    /** 打开某个 App，pkg 是包名 */
    data class LaunchApp(val pkg: String) : Command()

    /** 发短信，number 收件号码，text 短信内容 */
    data class SendSms(val number: String, val text: String) : Command()

    /** Tiyo下发的建议回复：contact 联系人，text 建议话术（半自动回微信）*/
    data class Suggest(val contact: String, val text: String) : Command()
}
