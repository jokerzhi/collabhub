/**
 * 文档模块（Document）。
 *
 * <p>负责：文档树、块级内容、版本与 diff、评论、分享链接、实时协同。
 * 对应需求 FR-DOC-001 ~ FR-DOC-016、FR-CMT-001 ~ FR-CMT-006、FR-RT-001 ~ FR-RT-007。
 *
 * <p>这是整个产品最核心也最复杂的模块。M3 引入 CRDT 之后，
 * 文档内容的写入路径会是「协同服务 → 快照 + 增量」，而不是简单的 UPDATE。
 *
 * <p>数据库版本号段：V3001 ~ V3999。
 */
package com.collabhub.document;
