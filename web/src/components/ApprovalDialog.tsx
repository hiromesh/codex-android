import { ApprovalDecision, CodexEvent } from "../types";

/**
 * §6.1 审批弹窗：必须四选一，不允许直接 dismiss（协议要求必须应答）。
 */
export function ApprovalDialog({
  request,
  onDecision,
}: {
  request: CodexEvent & { type: "approvalRequest" };
  onDecision: (decision: ApprovalDecision) => void;
}) {
  return (
    <div className="approval-overlay">
      <div className="approval-card">
        <div className="approval-title">命令执行审批</div>
        <div className="approval-command">
          <code>{request.command}</code>
          {request.cwd && <div className="approval-cwd">cwd: {request.cwd}</div>}
        </div>
        {request.reason && <div className="approval-reason">{request.reason}</div>}
        <button className="btn btn-primary" onClick={() => onDecision("accept")}>
          批准本次
        </button>
        <button className="btn" onClick={() => onDecision("acceptForSession")}>
          批准，本会话内不再询问
        </button>
        <button className="btn" onClick={() => onDecision("decline")}>
          拒绝
        </button>
        <button className="btn btn-danger-text" onClick={() => onDecision("cancel")}>
          拒绝并中断本轮
        </button>
      </div>
    </div>
  );
}
