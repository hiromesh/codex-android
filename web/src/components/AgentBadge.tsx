import { AgentTypeId, agentTypeFromWireValue } from "../types";

/** Agent 类型的字母徽章：彩色圆 + 单字母，用来在卡片/设置里区分 codex、kimi 等。 */
export function AgentBadge({ type, size = 24 }: { type: AgentTypeId; size?: number }) {
  const info = agentTypeFromWireValue(type);
  return (
    <span
      className="agent-badge"
      style={{
        width: size,
        height: size,
        backgroundColor: info.badgeColor,
        fontSize: Math.round(size * 0.48),
        lineHeight: `${size}px`,
      }}
      aria-label={info.displayName}
    >
      {info.badgeLetter}
    </span>
  );
}
