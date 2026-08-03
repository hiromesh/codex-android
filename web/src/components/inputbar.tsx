import { useEffect, useRef, useState } from "react";
import { ModelInfo, TokenUsage } from "../types";
import { CheckIcon, ChevronDownIcon, ChevronLeftIcon, ChevronRightIcon, MicIcon, SendIcon, StopIcon } from "./icons";

interface InputBarProps {
  generating: boolean;
  model: string;
  effort: string;
  models: ModelInfo[];
  onSelectConfiguration: (model: string, effort: string) => void;
  tokenUsage: TokenUsage | null;
  asrTranscript: string | null;
  asrRecording: boolean;
  onToggleAsr: () => void;
  onStopAsr: () => void;
  onSend: (text: string) => void;
  onInterrupt: () => void;
}

export function ChatInputBar(props: InputBarProps) {
  const { generating, model, effort, models, onSelectConfiguration, tokenUsage, asrTranscript, asrRecording } = props;
  const [text, setText] = useState("");
  const [voicePrefix, setVoicePrefix] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // ASR 的 result.text 是“本次完整识别结果”而不是 delta。记录启动前文本后每次替换尾部，
  // 既能实时上屏，也不会因服务端重复返回全文而重复追加。
  useEffect(() => {
    if (asrRecording) setVoicePrefix(text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asrRecording]);
  useEffect(() => {
    if (voicePrefix != null && asrTranscript != null) setText(voicePrefix + asrTranscript);
  }, [asrTranscript, voicePrefix]);

  const send = () => {
    const message = text.trim();
    if (!message || generating) return;
    // 发送时不接收停麦克风后的最终回包，确保当前输入就是被发出的内容。
    props.onStopAsr();
    setVoicePrefix(null);
    props.onSend(message);
    setText("");
    textareaRef.current?.focus();
  };

  return (
    <div className="input-bar">
      <textarea
        ref={textareaRef}
        className="input-text"
        placeholder="给 Codex 发消息…"
        value={text}
        rows={1}
        onChange={(e) => {
          const next = e.target.value;
          // 用户手动输入时立即停掉 ASR，防止识别结果覆盖手动输入；
          // 语义与发送按钮一致（onStopAsr + 丢弃语音前缀）。
          if (asrRecording) {
            props.onStopAsr();
            setVoicePrefix(null);
          }
          setText(next);
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
            e.preventDefault();
            send();
          }
        }}
      />
      <div className="input-tools">
        <ModelSelector model={model} effort={effort} models={models} onSelect={onSelectConfiguration} />
        <div className="input-tools-right">
          <button
            className={`asr-button ${asrRecording ? "asr-button-recording" : ""}`}
            title={asrRecording ? "停止语音识别" : "开始语音识别"}
            onClick={props.onToggleAsr}
          >
            <MicIcon size={15} />
          </button>
          <ContextUsageRing usage={tokenUsage} />
          {generating ? (
            <button className="send-button send-button-stop" title="停止生成" onClick={props.onInterrupt}>
              <span className="send-spinner" />
              <StopIcon size={9} />
            </button>
          ) : (
            <button className="send-button" title="发送" disabled={!text.trim()} onClick={send}>
              <SendIcon size={16} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/* ---------------- 模型选择器：模型 → 推理档位 两级菜单 ---------------- */

function ModelSelector({
  model,
  effort,
  models,
  onSelect,
}: {
  model: string;
  effort: string;
  models: ModelInfo[];
  onSelect: (model: string, effort: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [effortModelId, setEffortModelId] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
        setEffortModelId(null);
      }
    };
    window.addEventListener("pointerdown", onPointerDown);
    return () => window.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const selected = models.find((m) => m.id === model);
  const label = selected?.displayName ?? "GPT-5.6-Terra";

  return (
    <div className="model-selector" ref={rootRef}>
      <button className="model-trigger" onClick={() => setOpen(!open)}>
        <span className="model-label">{label}</span>
        <span className="model-dot">·</span>
        <span className="model-effort">{effortLabel(effort)}</span>
        <ChevronDownIcon size={14} />
      </button>
      {open && (
        <div className="model-menu">
          {effortModelId == null ? (
            <div className="model-list">
              {models.map((m) => (
                <button
                  key={m.id}
                  className={`model-row ${m.id === model ? "model-row-selected" : ""}`}
                  onClick={() => setEffortModelId(m.id)}
                >
                  <span>{m.displayName}</span>
                  {m.id === model ? <CheckIcon size={16} /> : <ChevronRightIcon size={16} />}
                </button>
              ))}
            </div>
          ) : (
            <EffortMenu
              model={models.find((m) => m.id === effortModelId)}
              selectedEffort={effortModelId === model ? effort : undefined}
              onBack={() => setEffortModelId(null)}
              onSelect={(selectedEffort) => {
                onSelect(effortModelId, selectedEffort);
                setOpen(false);
                setEffortModelId(null);
              }}
            />
          )}
        </div>
      )}
    </div>
  );
}

function EffortMenu({
  model,
  selectedEffort,
  onBack,
  onSelect,
}: {
  model?: ModelInfo;
  selectedEffort?: string;
  onBack: () => void;
  onSelect: (effort: string) => void;
}) {
  if (!model) return null;
  const current = selectedEffort ?? model.defaultReasoningEffort;
  return (
    <div className="effort-list">
      <button className="effort-back" onClick={onBack}>
        <ChevronLeftIcon size={16} />
        <span>{model.displayName}</span>
      </button>
      <div className="effort-divider" />
      {model.supportedReasoningEfforts.map((item) => (
        <button
          key={item}
          className={`effort-row ${item === current ? "effort-row-selected" : ""}`}
          onClick={() => onSelect(item)}
        >
          <span>{effortLabel(item)}</span>
          {item === current && <CheckIcon size={16} />}
        </button>
      ))}
    </div>
  );
}

export function effortLabel(effort: string): string {
  return effort.charAt(0).toUpperCase() + effort.slice(1);
}

/* ---------------- 上下文占用环 ---------------- */

export function ContextUsageRing({ usage }: { usage: TokenUsage | null }) {
  if (!usage || usage.contextWindow <= 0) return <span className="usage-ring usage-ring-empty" />;
  const fraction = Math.min(1, Math.max(0, usage.usedTokens / usage.contextWindow));
  const color = fraction >= 0.8 ? "var(--error)" : "var(--on-surface-variant)";
  const radius = 11;
  const circumference = 2 * Math.PI * radius;
  return (
    <span className="usage-ring" title={`${usage.usedTokens} / ${usage.contextWindow} tokens`}>
      <svg width="26" height="26" viewBox="0 0 26 26">
        <circle cx="13" cy="13" r={radius} fill="none" stroke="var(--surface)" strokeWidth="2.5" />
        <circle
          cx="13"
          cy="13"
          r={radius}
          fill="none"
          stroke={color}
          strokeWidth="2.5"
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - fraction)}
          transform="rotate(-90 13 13)"
        />
      </svg>
      <span className="usage-text" style={{ color }}>{Math.round(fraction * 100)}</span>
    </span>
  );
}
