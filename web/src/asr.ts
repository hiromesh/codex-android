import { AppSettings } from "./types";

/**
 * 火山引擎大模型流式 ASR 的最小客户端（与 Android StreamingAsrClient 同协议）。
 * 浏览器 WebSocket 不能自定义 X-Api-* 请求头，因此连接本服务 /ws/asr 代理，
 * 由服务端注入鉴权头；麦克风 PCM s16le 16kHz 每 200ms 分包 gzip 后发送。
 */

export interface AsrSession {
  stop(): void;
}

const SAMPLE_RATE = 16_000;
/** 200ms, PCM 16-bit mono */
const AUDIO_FRAME_SAMPLES = SAMPLE_RATE / 5;
const FINAL_RESPONSE_TIMEOUT_MS = 3_000;
const MESSAGE_TYPE_SERVER_RESPONSE = 0x09;
const MESSAGE_TYPE_ERROR = 0x0f;
const FLAG_SEQUENCE = 0x01;
const FLAG_FINAL_SEQUENCE = 0x03;
const COMPRESSION_GZIP = 0x01;

interface ServerResponse {
  text?: string;
  isFinal: boolean;
}

export function startAsr(
  settings: AppSettings,
  onTranscript: (text: string) => void,
  onFailure: (message: string) => void,
): AsrSession {
  if (!settings.asrAppKey.trim()) throw new Error("请先在设置中填写 ASR App Key（App ID）");
  if (!settings.asrAccessKey.trim()) throw new Error("请先在设置中填写 ASR Access Key（Access Token）");
  if (!/^ws:\/\//.test(settings.asrUrl.trim()) && !/^wss:\/\//.test(settings.asrUrl.trim())) {
    throw new Error("ASR 地址必须以 ws:// 或 wss:// 开头");
  }
  if (!settings.asrResourceId.trim()) throw new Error("请先在设置中填写 ASR 资源 ID");
  const params = new URLSearchParams({
    url: settings.asrUrl,
    appKey: settings.asrAppKey,
    accessKey: settings.asrAccessKey,
    resourceId: settings.asrResourceId,
  });
  const socket = new WebSocket(`/ws/asr?${params.toString()}`);
  socket.binaryType = "arraybuffer";

  let stopping = false;
  let finished = false;
  let recorder: PcmRecorder | null = null;
  let stopTimer: number | null = null;
  /** 保证 gzip 帧按序发送 */
  let sendChain: Promise<void> = Promise.resolve();

  const fail = (message: string) => {
    if (finished) return;
    finished = true;
    if (stopTimer != null) clearTimeout(stopTimer);
    recorder?.dispose();
    socket.close();
    onFailure(message);
  };

  const finishSilently = () => {
    if (finished) return;
    finished = true;
    if (stopTimer != null) clearTimeout(stopTimer);
    recorder?.dispose();
    socket.close();
  };

  const sendAudio = (audio: Uint8Array, isLast: boolean) => {
    sendChain = sendChain.then(async () => {
      try {
        const frame = await buildAudioFrame(audio, isLast);
        if (socket.readyState === WebSocket.OPEN) socket.send(frame);
      } catch (error) {
        fail(`语音数据发送失败：${error instanceof Error ? error.message : String(error)}`);
      }
    });
  };

  socket.onopen = async () => {
    if (stopping) {
      socket.close(1000, "cancelled before recording");
      return;
    }
    try {
      socket.send(await buildFullClientRequest());
      recorder = await startPcmRecorder((chunk) => sendAudio(chunk, false));
    } catch (error) {
      fail(`无法启动麦克风：${error instanceof Error ? error.message : String(error)}`);
    }
  };

  socket.onmessage = async (event) => {
    if (typeof event.data === "string") return;
    try {
      const response = await parseServerResponse(event.data as ArrayBuffer);
      if (response.text) onTranscript(response.text);
      if (response.isFinal) finishSilently();
    } catch (error) {
      fail(`语音识别响应解析失败：${error instanceof Error ? error.message : String(error)}`);
    }
  };

  socket.onclose = (event) => {
    if (!stopping && !finished) fail(`语音识别连接已关闭${event.reason ? `：${event.reason}` : ""}`);
  };
  socket.onerror = () => {
    /* 由 onclose 统一处理 */
  };

  return {
    /** 停止采集后发送负包，给服务端机会返回最终纠正后的文本。 */
    stop() {
      if (stopping) return;
      stopping = true;
      recorder?.flush();
      recorder?.dispose();
      sendAudio(new Uint8Array(0), true);
      stopTimer = window.setTimeout(() => finishSilently(), FINAL_RESPONSE_TIMEOUT_MS);
    },
  };
}

function buildFullClientRequest(): Promise<ArrayBuffer> {
  const payload = JSON.stringify({
    user: { uid: "codex-web" },
    audio: { format: "pcm", codec: "raw", rate: SAMPLE_RATE, bits: 16, channel: 1 },
    request: { model_name: "bigmodel", enable_itn: true, enable_punc: true, result_type: "full" },
  });
  // version=1, header size=4; full request + JSON + gzip.
  return frame(new Uint8Array([0x11, 0x10, 0x11, 0x00]), gzipBytes(utf8(payload)));
}

async function buildAudioFrame(audio: Uint8Array, isLast: boolean): Promise<ArrayBuffer> {
  // audio request + (last packet flag if needed) + raw bytes + gzip.
  const messageFlags = isLast ? 0x22 : 0x20;
  return frame(new Uint8Array([0x11, messageFlags, 0x01, 0x00]), gzipBytes(audio));
}

async function frame(header: Uint8Array, compressedPayload: Promise<Uint8Array>): Promise<ArrayBuffer> {
  const payload = await compressedPayload;
  const out = new Uint8Array(header.length + 4 + payload.length);
  out.set(header, 0);
  const view = new DataView(out.buffer);
  view.setUint32(header.length, payload.length, false);
  out.set(payload, header.length + 4);
  return out.buffer;
}

async function parseServerResponse(buffer: ArrayBuffer): Promise<ServerResponse> {
  const frame = new Uint8Array(buffer);
  if (frame.length < 8) throw new Error("响应帧过短");
  const headerSize = (frame[0] & 0x0f) * 4;
  if (headerSize < 4 || frame.length < headerSize + 4) throw new Error("响应头长度无效");
  const messageType = (frame[1] >> 4) & 0x0f;
  const flags = frame[1] & 0x0f;
  const compression = frame[2] & 0x0f;
  let offset = headerSize;
  if (flags === FLAG_SEQUENCE || flags === FLAG_FINAL_SEQUENCE) {
    if (frame.length < offset + 8) throw new Error("响应帧缺少 sequence 或 payload 长度");
    offset += 4; // 服务端 sequence；应用层不需要它。
  }
  if (messageType === MESSAGE_TYPE_ERROR) {
    if (frame.length < offset + 8) throw new Error("错误响应帧不完整");
    const errorCode = new DataView(frame.buffer, offset, 4).getInt32(0, false);
    offset += 4;
    const payload = await readPayload(frame, offset, compression);
    const detail = utf8Decode(payload) || `错误码 ${errorCode}`;
    throw new Error(`服务端拒绝语音识别：${detail}`);
  }
  if (messageType !== MESSAGE_TYPE_SERVER_RESPONSE) {
    throw new Error(`不支持的服务端消息类型 ${messageType}`);
  }
  const payload = await readPayload(frame, offset, compression);
  const json = JSON.parse(utf8Decode(payload));
  const text = json.result?.text;
  return { text: typeof text === "string" && text ? text : undefined, isFinal: flags === FLAG_FINAL_SEQUENCE };
}

async function readPayload(frame: Uint8Array, offset: number, compression: number): Promise<Uint8Array> {
  if (frame.length < offset + 4) throw new Error("响应帧缺少 payload 长度");
  const payloadSize = new DataView(frame.buffer, offset, 4).getInt32(0, false);
  if (payloadSize < 0 || frame.length < offset + 4 + payloadSize) throw new Error("响应 payload 长度无效");
  const payload = frame.slice(offset + 4, offset + 4 + payloadSize);
  return compression === COMPRESSION_GZIP ? gunzipBytes(payload) : payload;
}

function toArrayBuffer(data: Uint8Array): ArrayBuffer {
  return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer;
}

async function gzipBytes(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([toArrayBuffer(data)]).stream().pipeThrough(new CompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function gunzipBytes(data: Uint8Array): Promise<Uint8Array> {
  const stream = new Blob([toArrayBuffer(data)]).stream().pipeThrough(new DecompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function utf8(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

function utf8Decode(data: Uint8Array): string {
  return new TextDecoder().decode(data);
}

interface PcmRecorder {
  flush(): void;
  dispose(): void;
}

/**
 * getUserMedia → AudioWorklet：重采样到 16kHz、转 s16le、每 200ms 攒一包投递。
 */
async function startPcmRecorder(
  onChunk: (chunk: Uint8Array) => void,
): Promise<PcmRecorder> {
  let stream: MediaStream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
  } catch (error) {
    throw new Error(`无法启动麦克风，请检查录音权限（${error instanceof Error ? error.message : String(error)}）`);
  }

  let context: AudioContext;
  try {
    context = new AudioContext({ sampleRate: SAMPLE_RATE });
  } catch {
    context = new AudioContext(); // 不支持指定采样率时回退，由 worklet 重采样
  }
  await context.audioWorklet.addModule(workletUrl);
  const source = context.createMediaStreamSource(stream);
  const node = new AudioWorkletNode(context, "pcm-processor", { numberOfInputs: 1, numberOfOutputs: 0 });
  node.port.onmessage = (event) => {
    if (event.data instanceof ArrayBuffer) onChunk(new Uint8Array(event.data));
  };
  source.connect(node);
  await context.resume();

  let disposed = false;
  return {
    flush() {
      if (!disposed) node.port.postMessage({ type: "stop" });
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      node.disconnect();
      source.disconnect();
      stream.getTracks().forEach((track) => track.stop());
      void context.close();
    },
  };
}

const WORKLET_SOURCE = `
class PCMProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.out = new Int16Array(${AUDIO_FRAME_SAMPLES});
    this.outCount = 0;
    this.step = sampleRate / ${SAMPLE_RATE}; // 每个输出样本对应的输入样本数
    this.pos = 0;                            // 下一个输出样本位置（输入样本单位）
    this.prev = 0;
    this.inputIndex = 0;
    this.port.onmessage = (e) => {
      if (e.data && e.data.type === 'stop') this.flush();
    };
  }
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) {
      for (let i = 0; i < channel.length; i++) {
        const s = channel[i];
        const idx = this.inputIndex;
        while (this.pos <= idx) {
          const t = this.pos - (idx - 1); // 0..1 线性插值
          this.pushSample(this.prev + (s - this.prev) * t);
          this.pos += this.step;
        }
        this.prev = s;
        this.inputIndex = idx + 1;
      }
    }
    return true;
  }
  pushSample(v) {
    const clamped = Math.max(-1, Math.min(1, v));
    this.out[this.outCount++] = (clamped < 0 ? clamped * 0x8000 : clamped * 0x7fff) | 0;
    if (this.outCount === this.out.length) this.post();
  }
  flush() {
    if (this.outCount > 0) this.post();
  }
  post() {
    const buffer = this.out.buffer;
    this.port.postMessage(buffer, [buffer]);
    this.out = new Int16Array(${AUDIO_FRAME_SAMPLES});
    this.outCount = 0;
  }
}
registerProcessor('pcm-processor', PCMProcessor);
`;

const workletUrl = URL.createObjectURL(new Blob([WORKLET_SOURCE], { type: "application/javascript" }));
