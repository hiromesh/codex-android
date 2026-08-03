import { useMemo } from "react";
import { marked } from "marked";
import DOMPurify from "dompurify";

/**
 * CommonMark + GFM 渲染（对应 Android 的 commonmark + TablesExtension）。
 * 图片与 Android 一致只展示 alt 文本；链接新窗口打开，由 CSS 控制样式。
 */
export function MarkdownMessage({ markdown, streaming }: { markdown: string; streaming?: boolean }) {
  const html = useMemo(() => renderMarkdown(markdown), [markdown]);
  return <div className="md" dangerouslySetInnerHTML={{ __html: streaming ? html + " <span class=\"md-cursor\">▍</span>" : html }} />;
}

function renderMarkdown(source: string): string {
  const raw = marked.parse(source, { gfm: true, breaks: false }) as string;
  const clean = DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
  const doc = new DOMParser().parseFromString(clean, "text/html");
  doc.querySelectorAll("img").forEach((img) => {
    img.replaceWith(document.createTextNode(img.alt || img.getAttribute("src") || ""));
  });
  // 站外链接新窗口打开
  doc.querySelectorAll("a[href]").forEach((a) => {
    a.setAttribute("target", "_blank");
    a.setAttribute("rel", "noreferrer");
  });
  return doc.body.innerHTML;
}
