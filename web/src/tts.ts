import { AppSettings } from "./types";
import { settingsStore } from "./settings";

/**
 * 豆包语音合成大模型 V3 双向流式（wss://openspeech.bytedance.com/api/v3/tts/bidirection）
 * 的最小接入：agent 回答的文本增量直接喂进来，PCM 音频边收边播。
 * 逻辑与 Android VolcengineTtsManager 一一对应；浏览器端用 Web Audio 播放 PCM。
 *
 * 按文档最佳实践复用一条 WebSocket：StartConnection 一次，随后每条 agent 回答
 * 对应一个 session（StartSession → TaskRequest* → FinishSession）。
 * 工具调用、思考过程等其他事件不经过这里，只有 agent 正文会朗读。
 */

const SAMPLE_RATE = 24_000;
const AUDIO_FORMAT = "pcm";

const EVENT_START_CONNECTION = 1;
const EVENT_CONNECTION_STARTED = 50;
const EVENT_CONNECTION_FAILED = 51;
const EVENT_START_SESSION = 100;
const EVENT_CANCEL_SESSION = 101;
const EVENT_FINISH_SESSION = 102;
const EVENT_SESSION_STARTED = 150;
const EVENT_SESSION_CANCELED = 151;
const EVENT_SESSION_FINISHED = 152;
const EVENT_SESSION_FAILED = 153;
const EVENT_TASK_REQUEST = 200;
const EVENT_TTS_RESPONSE = 352;

const MESSAGE_TYPE_ERROR = 0x0f;
const FLAG_WITH_EVENT = 0x04;
const SERIALIZATION_JSON = 0x01;
const COMPRESSION_GZIP = 0x01;

const EMPTY_PAYLOAD = new TextEncoder().encode("{}");

export class TtsManager {
  private socket: WebSocket | null = null;
  private connectionReady = false;
  private connectionFingerprint: string | null = null;
  private sessionId: string | null = null;
  private sessionStarted = false;
  private finishRequested = false;
  /** 上一个 session 收尾期间，下一条回答已经完整结束，新 session 一开就要立即 Finish。 */
  private queuedFinish = false;
  private pendingText = "";
  private sessionSettings: AppSettings | null = null;
  private filter = new SpeakTextFilter();
  private player: PcmPlayer | null = null;
  /** 含已 finish 仍在播尾音的实例；stop / 新 session 必须全部 interrupt，不能丢引用。 */
  private livePlayers: PcmPlayer[] = [];
  private ctx: AudioContext | null = null;
  private unsubSettings: () => void;

  constructor() {
    // 关掉开关立刻停止播报；连接级配置变化后下次合成用新配置重建连接。
    this.unsubSettings = settingsStore.subscribe(() => this.onSettingsChanged());
  }

  /** agent 回答正文的一个流式增量（仅 agentMessage 会走到这里）。 */
  onAgentDelta(delta: string) {
    const settings = settingsStore.get();
    if (!settings.ttsEnabled || !settings.ttsApiKey.trim()) return;
    const speakable = this.filter.feed(delta);
    if (!speakable) return;
    this.pendingText += speakable;
    if (this.sessionId == null) {
      this.beginSession(settings);
    } else if (this.sessionStarted && !this.finishRequested) {
      // 正在收尾的 session 不能再塞文本，缓冲到下个 session 一起发。
      this.flushPending();
    }
  }

  /** 一条 agent 回答文本结束（item/completed），文本发完后关闭本次合成 session。 */
  onAgentMessageFinished() {
    this.filter.reset();
    const activeSession = this.sessionId;
    if (!activeSession) {
      this.pendingText = "";
      return;
    }
    // 上一个 session 还在收尾：这条回答的文本已缓冲，标记其结束后立即 Finish。
    if (this.finishRequested) {
      this.queuedFinish = true;
      return;
    }
    this.finishRequested = true;
    if (this.sessionStarted) {
      this.flushPending();
      this.sendFrame(EVENT_FINISH_SESSION, activeSession, EMPTY_PAYLOAD);
    }
  }

  /** 立即清空待合成文本并中断当前播报（用户打断、发新消息、离开会话等）。 */
  stop() {
    this.filter.reset();
    this.pendingText = "";
    this.finishRequested = false;
    this.queuedFinish = false;
    const activeSession = this.sessionId;
    this.sessionId = null;
    this.sessionStarted = false;
    if (activeSession != null && this.connectionReady) {
      this.sendFrame(EVENT_CANCEL_SESSION, activeSession, EMPTY_PAYLOAD);
    }
    this.interruptAllPlayers();
  }

  release() {
    this.unsubSettings();
    this.stop();
    this.teardown();
    void this.ctx?.close();
  }

  private onSettingsChanged() {
    const settings = settingsStore.get();
    if (!settings.ttsEnabled) {
      this.stop();
      this.teardown();
    } else if (this.socket != null && this.connectionFingerprint !== fingerprint(settings)) {
      this.teardown();
    }
  }

  private beginSession(settings: AppSettings) {
    this.sessionSettings = settings;
    this.sessionId = crypto.randomUUID();
    this.sessionStarted = false;
    this.finishRequested = false;
    if (this.socket != null && this.connectionFingerprint !== fingerprint(settings)) {
      this.teardown();
    }
    if (this.socket == null) this.connect(settings);
    else this.maybeStartSession();
  }

  private connect(settings: AppSettings) {
    this.connectionReady = false;
    this.connectionFingerprint = fingerprint(settings);
    const params = new URLSearchParams({
      url: settings.ttsUrl,
      apiKey: settings.ttsApiKey,
      resourceId: settings.ttsResourceId,
    });
    const socket = new WebSocket(`/ws/tts?${params.toString()}`);
    socket.binaryType = "arraybuffer";
    this.socket = socket;
    socket.onopen = () => {
      // 建连后第一帧 StartConnection，收到 ConnectionStarted 才算可用。
      this.sendFrame(EVENT_START_CONNECTION, null, EMPTY_PAYLOAD);
    };
    socket.onmessage = (event) => {
      try {
        if (typeof event.data === "string") return;
        void this.handleFrame(new Uint8Array(event.data as ArrayBuffer));
      } catch (error) {
        console.warn("TTS 响应解析失败：", error instanceof Error ? error.message : error);
      }
    };
    socket.onerror = () => {
      /* 由 onclose 统一处理 */
    };
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.teardown();
    };
  }

  private maybeStartSession() {
    const activeSession = this.sessionId;
    const settings = this.sessionSettings;
    if (!activeSession || !settings || !this.connectionReady || this.sessionStarted) return;
    const payload = new TextEncoder().encode(
      JSON.stringify({
        event: EVENT_START_SESSION,
        namespace: "BidirectionalTTS",
        user: { uid: "codex-android" },
        req_params: {
          speaker: settings.ttsSpeaker,
          audio_params: {
            format: AUDIO_FORMAT,
            sample_rate: SAMPLE_RATE,
            speech_rate: settings.ttsSpeechRate,
          },
          // 服务端再过滤一遍 markdown 语法（标题/加粗/表格等），避免读出符号。
          additions: JSON.stringify({ disable_markdown_filter: true }),
        },
      }),
    );
    this.sendFrame(EVENT_START_SESSION, activeSession, payload);
  }

  private flushPending() {
    const activeSession = this.sessionId;
    if (!activeSession || !this.sessionStarted || !this.pendingText) return;
    const payload = new TextEncoder().encode(
      JSON.stringify({ event: EVENT_TASK_REQUEST, req_params: { text: this.pendingText } }),
    );
    this.pendingText = "";
    this.sendFrame(EVENT_TASK_REQUEST, activeSession, payload);
  }

  private sendFrame(event: number, sessionId: string | null, payload: Uint8Array) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;
    this.socket.send(uplinkFrame(event, sessionId, payload));
  }

  private teardown() {
    this.interruptAllPlayers();
    this.socket?.close();
    this.socket = null;
    this.connectionReady = false;
    this.connectionFingerprint = null;
    this.sessionId = null;
    this.sessionStarted = false;
    this.finishRequested = false;
    this.queuedFinish = false;
    this.pendingText = "";
    this.filter.reset();
  }

  private interruptAllPlayers() {
    for (const player of this.livePlayers) player.interrupt();
    this.livePlayers = [];
    this.player = null;
  }

  private startPlayer(): PcmPlayer {
    this.interruptAllPlayers();
    const ctx = this.ensureContext();
    const player = new PcmPlayer(ctx, ctx.sampleRate === SAMPLE_RATE ? SAMPLE_RATE : ctx.sampleRate);
    this.player = player;
    this.livePlayers.push(player);
    return player;
  }

  private ensureContext(): AudioContext {
    if (!this.ctx) {
      try {
        this.ctx = new AudioContext({ sampleRate: SAMPLE_RATE });
      } catch {
        // 不支持指定采样率时回退，由 PcmPlayer 线性重采样。
        this.ctx = new AudioContext();
      }
    }
    // 浏览器自动播放策略：首次合成通常在用户发消息之后，直接 resume 即可。
    if (this.ctx.state === "suspended") void this.ctx.resume();
    return this.ctx;
  }

  /** SessionFinished 后复位 session 状态；连接保留，缓冲的文本直接开新 session。 */
  private finishSession() {
    const settings = this.sessionSettings;
    const startNext = this.pendingText.length > 0 && settings != null;
    if (startNext) {
      // 下一段立刻开：打断上一段，只播最新。
      this.interruptAllPlayers();
    } else {
      // 本轮最后一段：允许播完尾音，但保留 livePlayers，便于 stop / 下一段打断。
      this.player?.finish();
      this.player = null;
    }
    this.sessionId = null;
    this.sessionStarted = false;
    this.finishRequested = false;
    const finishNext = this.queuedFinish;
    this.queuedFinish = false;
    if (startNext) {
      this.beginSession(settings!);
      // 文本已经到齐，等 SessionStarted 后 flush 并立即 FinishSession。
      if (finishNext) this.finishRequested = true;
    }
  }

  private async handleFrame(frame: Uint8Array) {
    if (frame.length < 4) throw new Error("响应帧过短");
    const headerSize = (frame[0] & 0x0f) * 4;
    const messageType = (frame[1] >> 4) & 0x0f;
    const flags = frame[1] & 0x0f;
    const serialization = (frame[2] >> 4) & 0x0f;
    const compression = frame[2] & 0x0f;
    let offset = headerSize;

    if (messageType === MESSAGE_TYPE_ERROR) {
      const errorCode = readInt32BE(frame, offset);
      offset += 4;
      const payloadSize = readInt32BE(frame, offset);
      offset += 4;
      const message = utf8Decode(frame.slice(offset, offset + payloadSize));
      console.warn(`TTS 服务端错误 code=${errorCode} message=${message}`);
      this.teardown();
      return;
    }

    let event = 0;
    if (flags & FLAG_WITH_EVENT) {
      event = readInt32BE(frame, offset);
      offset += 4;
    }
    // 连接类事件带 connect id，其余（会话类/数据类）带 session id，解析后直接跳过。
    if (event !== 0 && offset + 4 <= frame.length) {
      const idSize = readInt32BE(frame, offset);
      offset += 4 + idSize;
    }
    const payloadSize = offset + 4 <= frame.length ? readInt32BE(frame, offset) : 0;
    offset += 4;
    const payloadEnd = Math.min(offset + payloadSize, frame.length);
    let payload = frame.slice(offset, payloadEnd);
    if (compression === COMPRESSION_GZIP && serialization === SERIALIZATION_JSON && payload.length > 0) {
      payload = await gunzipBytes(payload);
    }

    switch (event) {
      case EVENT_CONNECTION_STARTED:
        this.connectionReady = true;
        this.maybeStartSession();
        break;
      case EVENT_SESSION_STARTED:
        this.sessionStarted = true;
        this.startPlayer();
        this.flushPending();
        if (this.finishRequested && this.sessionId != null) {
          this.sendFrame(EVENT_FINISH_SESSION, this.sessionId, EMPTY_PAYLOAD);
        }
        break;
      case EVENT_TTS_RESPONSE:
        this.player?.write(payload);
        break;
      case EVENT_SESSION_FINISHED:
        this.finishSession();
        break;
      case EVENT_SESSION_CANCELED:
      case EVENT_SESSION_FAILED:
        this.interruptAllPlayers();
        this.sessionId = null;
        this.sessionStarted = false;
        this.finishRequested = false;
        this.queuedFinish = false;
        this.pendingText = "";
        break;
      case EVENT_CONNECTION_FAILED:
        this.teardown();
        break;
    }
  }
}

function fingerprint(settings: AppSettings): string {
  return [settings.ttsUrl, settings.ttsApiKey, settings.ttsResourceId].join("|");
}

/** 上行帧：v1 4 字节头 + full-client request + event + 可选 session id + JSON/raw payload。 */
function uplinkFrame(event: number, sessionId: string | null, payload: Uint8Array): ArrayBuffer {
  const sid = sessionId ? new TextEncoder().encode(sessionId) : null;
  const size = 4 + 4 + (sid ? 4 + sid.length : 0) + 4 + payload.length;
  const out = new Uint8Array(size);
  const view = new DataView(out.buffer);
  // version=1 headerSize=4；full-client request + 带 event；JSON 不压缩。
  out[0] = 0x11;
  out[1] = 0x14;
  out[2] = 0x10;
  out[3] = 0x00;
  view.setInt32(4, event, false);
  let offset = 8;
  if (sid) {
    view.setInt32(offset, sid.length, false);
    offset += 4;
    out.set(sid, offset);
    offset += sid.length;
  }
  view.setInt32(offset, payload.length, false);
  out.set(payload, offset + 4);
  return out.buffer;
}

function readInt32BE(frame: Uint8Array, offset: number): number {
  return new DataView(frame.buffer, frame.byteOffset + offset, 4).getInt32(0, false);
}

function utf8Decode(data: Uint8Array): string {
  return new TextDecoder().decode(data);
}

async function gunzipBytes(data: Uint8Array): Promise<Uint8Array<ArrayBuffer>> {
  // 复制出独立 ArrayBuffer 视图，避免 Blob 对 SharedArrayBuffer 视图的拒绝。
  const copy = new Uint8Array(data);
  const stream = new Blob([copy]).stream().pipeThrough(new DecompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/* ---------------- 文本过滤（对应 SpeakTextFilter） ---------------- */

/**
 * 流式朗读文本过滤：agent 的回答是 markdown，直接整段读代码块体验很差，
 * 因此在喂给 TTS 前把 ``` 围栏代码块整体剔除，行内 `code` 的反引号去掉但保留内容。
 * 服务端还会再做一层 markdown 语法过滤（disable_markdown_filter），这里只处理代码。
 *
 * delta 是任意切分的字符流，反引号可能跨包，末尾的连续反引号会先挂起，
 * 等到下一个非反引号字符（或 reset）再判定它是围栏开关还是行内代码。
 */
class SpeakTextFilter {
  private inFence = false;
  private heldBackticks = 0;

  feed(delta: string): string {
    let out = "";
    for (const char of delta) {
      if (char === "`") {
        this.heldBackticks++;
        continue;
      }
      this.resolveBackticks();
      if (!this.inFence) out += char;
    }
    return out;
  }

  reset() {
    this.inFence = false;
    this.heldBackticks = 0;
  }

  private resolveBackticks() {
    if (this.heldBackticks >= 3) this.inFence = !this.inFence;
    this.heldBackticks = 0;
  }
}

/* ---------------- PCM 播放（对应 PcmStreamPlayer，Web Audio 实现） ---------------- */

class PcmPlayer {
  private queue: AudioBuffer[] = [];
  private nextTime = 0;
  private pumping = false;
  private interrupted = false;

  constructor(
    private readonly ctx: AudioContext,
    private readonly sampleRate: number,
  ) {}

  write(chunk: Uint8Array) {
    if (this.interrupted || chunk.length < 2) return;
    const floats = pcmToFloats(chunk, this.ctx.sampleRate, this.sampleRate);
    if (floats.length === 0) return;
    const buffer = this.ctx.createBuffer(1, floats.length, this.ctx.sampleRate);
    buffer.copyToChannel(floats, 0);
    this.queue.push(buffer);
    this.pump();
  }

  /** 允许尾音播完（不再写新块）。 */
  finish() {
    /* 队列播完自然停止 */
  }

  interrupt() {
    this.interrupted = true;
    this.queue = [];
    this.pumping = false;
  }

  private pump() {
    if (this.pumping || this.queue.length === 0 || this.ctx.state === "closed") return;
    this.pumping = true;
    const pumpNext = () => {
      if (this.interrupted) {
        this.pumping = false;
        return;
      }
      const buffer = this.queue.shift();
      if (!buffer) {
        this.pumping = false;
        return;
      }
      const source = this.ctx.createBufferSource();
      source.buffer = buffer;
      source.connect(this.ctx.destination);
      const now = this.ctx.currentTime;
      if (this.nextTime < now) this.nextTime = now;
      const startAt = Math.max(now + 0.02, this.nextTime);
      source.start(startAt);
      this.nextTime = startAt + buffer.duration;
      source.onended = pumpNext;
    };
    pumpNext();
  }
}

/** s16le PCM → Float32Array；采样率不一致时线性重采样到目标（浏览器上下文）采样率。 */
function pcmToFloats(chunk: Uint8Array, targetRate: number, sourceRate: number): Float32Array<ArrayBuffer> {
  const samples = new Int16Array(chunk.buffer, chunk.byteOffset, Math.floor(chunk.length / 2));
  const floats = new Float32Array(samples.length);
  for (let i = 0; i < samples.length; i++) {
    floats[i] = samples[i] / 32768;
  }
  if (targetRate === sourceRate || floats.length <= 1) return floats;
  const ratio = targetRate / sourceRate;
  const out = new Float32Array(Math.max(1, Math.floor(floats.length * ratio)));
  for (let i = 0; i < out.length; i++) {
    const pos = i / ratio;
    const index = Math.floor(pos);
    if (index >= floats.length - 1) {
      out[i] = floats[floats.length - 1];
    } else {
      const frac = pos - index;
      out[i] = floats[index] * (1 - frac) + floats[index + 1] * frac;
    }
  }
  return out;
}

/** 全局单例：Agent 回答流式播报（对应 Android ServiceLocator.ttsManager）。 */
export const ttsManager = new TtsManager();
