function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function renderInline(text) {
    return escapeHtml(text)
        .replace(/`([^`]+)`/g, "<code>$1</code>")
        .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
        .replace(/\*([^*]+)\*/g, "<em>$1</em>");
}

export function renderMarkdown(markdown) {
    const source = String(markdown ?? "").replace(/\r\n/g, "\n");
    if (!source.trim()) {
        return "<div class=\"empty-state\">暂无内容。</div>";
    }

    const blocks = [];
    const lines = source.split("\n");
    let index = 0;

    while (index < lines.length) {
        const line = lines[index];

        if (line.startsWith("```")) {
            const language = line.slice(3).trim();
            const chunk = [];
            index += 1;
            while (index < lines.length && !lines[index].startsWith("```")) {
                chunk.push(lines[index]);
                index += 1;
            }
            blocks.push(`<pre><code class="language-${escapeHtml(language)}">${escapeHtml(chunk.join("\n"))}</code></pre>`);
            index += 1;
            continue;
        }

        if (!line.trim()) {
            index += 1;
            continue;
        }

        const heading = line.match(/^(#{1,3})\s+(.*)$/);
        if (heading) {
            const level = heading[1].length;
            blocks.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
            index += 1;
            continue;
        }

        if (line.startsWith("> ")) {
            const quote = [];
            while (index < lines.length && lines[index].startsWith("> ")) {
                quote.push(renderInline(lines[index].slice(2)));
                index += 1;
            }
            blocks.push(`<blockquote>${quote.join("<br>")}</blockquote>`);
            continue;
        }

        if (/^[-*]\s+/.test(line)) {
            const items = [];
            while (index < lines.length && /^[-*]\s+/.test(lines[index])) {
                items.push(`<li>${renderInline(lines[index].replace(/^[-*]\s+/, ""))}</li>`);
                index += 1;
            }
            blocks.push(`<ul>${items.join("")}</ul>`);
            continue;
        }

        if (/^\d+\.\s+/.test(line)) {
            const items = [];
            while (index < lines.length && /^\d+\.\s+/.test(lines[index])) {
                items.push(`<li>${renderInline(lines[index].replace(/^\d+\.\s+/, ""))}</li>`);
                index += 1;
            }
            blocks.push(`<ol>${items.join("")}</ol>`);
            continue;
        }

        const paragraph = [];
        while (index < lines.length && lines[index].trim() && !/^(#{1,3})\s+/.test(lines[index]) && !lines[index].startsWith("> ") && !/^[-*]\s+/.test(lines[index]) && !/^\d+\.\s+/.test(lines[index]) && !lines[index].startsWith("```")) {
            paragraph.push(renderInline(lines[index]));
            index += 1;
        }
        blocks.push(`<p>${paragraph.join("<br>")}</p>`);
    }

    return `<div class="markdown-preview">${blocks.join("")}</div>`;
}
