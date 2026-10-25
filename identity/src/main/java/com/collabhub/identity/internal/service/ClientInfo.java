package com.collabhub.identity.internal.service;

/**
 * 发起登录的客户端信息。
 *
 * <p>为什么由 Controller 提取后传进来，而不是在 Service 里直接读
 * {@code HttpServletRequest}：Service 一旦依赖 Servlet API，就没法在非 HTTP 场景
 * （定时任务、消息消费、单元测试）里复用了。保持 Service 只依赖普通数据。
 *
 * @param ipAddress 客户端 IP。可能来自 {@code X-Forwarded-For}，<b>仅用于展示和异常检测</b>，
 *                  绝不能用来做访问控制（这个头客户端可以随便伪造）
 * @param userAgent 原始 UA 串，用来在"设备管理"里显示成 "Chrome on Windows"
 */
public record ClientInfo(String ipAddress, String userAgent) {
}
