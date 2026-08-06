import { useEffect, useState } from "react";
import { PendingActionPrompt } from "../stores";
import { ReviewTarget } from "../types";

/**
 * 斜杠动作的二次确认/选择弹窗（对应 Android ActionPromptDialogs）。
 * 样式沿用删除会话确认弹窗的玻璃卡片。
 */
export function ActionPromptDialogs({
  prompt,
  onDismiss,
  onConfirmReview,
  onConfirmUndo,
  onConfirmShell,
}: {
  prompt: PendingActionPrompt | null;
  onDismiss: () => void;
  onConfirmReview: (target: ReviewTarget) => void;
  onConfirmUndo: (numTurns: number) => void;
  onConfirmShell: (command: string) => void;
}) {
  useEffect(() => {
    if (!prompt) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onDismiss();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [prompt, onDismiss]);

  if (!prompt) return null;
  switch (prompt.kind) {
    case "reviewTarget":
      return <ReviewTargetDialog onDismiss={onDismiss} onConfirm={onConfirmReview} />;
    case "confirmUndo":
      return (
        <div className="dialog-overlay" onClick={onDismiss}>
          <div className="dialog-card" role="alertdialog" aria-label="撤销轮次" onClick={(e) => e.stopPropagation()}>
            <div className="dialog-title">撤销末尾 {prompt.numTurns} 轮？</div>
            <div className="dialog-desc">只会删除对话历史，不会回滚 agent 已经改过的文件。</div>
            <div className="dialog-actions">
              <button className="btn" onClick={onDismiss}>
                取消
              </button>
              <button className="btn btn-primary" onClick={() => onConfirmUndo(prompt.numTurns)}>
                撤销
              </button>
            </div>
          </div>
        </div>
      );
    case "confirmShell":
      return (
        <div className="dialog-overlay" onClick={onDismiss}>
          <div className="dialog-card" role="alertdialog" aria-label="执行 Shell" onClick={(e) => e.stopPropagation()}>
            <div className="dialog-title">在会话上下文执行 Shell？</div>
            <div className="dialog-desc">此命令不受沙箱限制，将以 full access 执行：</div>
            <div className="dialog-subject mono">{prompt.command}</div>
            <div className="dialog-actions">
              <button className="btn" onClick={onDismiss}>
                取消
              </button>
              <button className="btn btn-primary" onClick={() => onConfirmShell(prompt.command)}>
                执行
              </button>
            </div>
          </div>
        </div>
      );
  }
}

function ReviewTargetDialog({
  onDismiss,
  onConfirm,
}: {
  onDismiss: () => void;
  onConfirm: (target: ReviewTarget) => void;
}) {
  const [branch, setBranch] = useState("main");
  const [custom, setCustom] = useState("");
  return (
    <div className="dialog-overlay" onClick={onDismiss}>
      <div className="dialog-card dialog-card-wide" role="alertdialog" aria-label="选择审查目标" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-title">选择审查目标</div>
        <button
          className="btn review-target-row"
          onClick={() => onConfirm({ type: "uncommittedChanges" })}
        >
          未提交改动
        </button>
        <div className="review-target-input-row">
          <input
            className="review-target-input"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            placeholder="相对分支"
            spellCheck={false}
          />
          <button
            className="btn btn-primary review-target-go"
            onClick={() => {
              const b = branch.trim();
              if (b) onConfirm({ type: "baseBranch", branch: b });
            }}
          >
            审查
          </button>
        </div>
        <input
          className="review-target-input"
          value={custom}
          onChange={(e) => setCustom(e.target.value)}
          placeholder="自定义指令"
          spellCheck={false}
        />
        <div className="dialog-actions">
          <button className="btn" onClick={onDismiss}>
            取消
          </button>
          <button
            className="btn btn-primary"
            onClick={() => {
              const text = custom.trim();
              if (text) onConfirm({ type: "custom", instructions: text });
            }}
          >
            按指令审查
          </button>
        </div>
      </div>
    </div>
  );
}
