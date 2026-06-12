// claude-proxy/server.js
//
// Express server that exposes POST /parse-order to the DAMIU POS Android app.
// Internally uses @anthropic-ai/claude-agent-sdk to invoke Claude — auth is
// the Max plan OAuth token stored at $HOME/.claude/.credentials.json (set by
// running `claude login` once inside this container).
//
// Auth: Bearer token (PROXY_TOKEN env var). Reject everything else with 401.

import express from 'express';
import { query } from '@anthropic-ai/claude-agent-sdk';

const app = express();
app.use(express.json({ limit: '64kb' }));

const TOKEN = process.env.PROXY_TOKEN;
if (!TOKEN) {
    console.error('FATAL: PROXY_TOKEN env var not set');
    process.exit(1);
}

const MODEL = process.env.CLAUDE_MODEL || 'claude-haiku-4-5';
const PORT = parseInt(process.env.PORT || '3000', 10);

// ---------------------------------------------------------------------------
// Prompt builder — mirrors ClaudeClient.java's buildPrompt() exactly so the
// Android app gets the same parsing behavior whether it calls Anthropic API
// directly or through this proxy.
function buildPrompt(senderName, senderPhone, message, products, customers) {
    const safe = (s) => (s || '').replace(/"/g, '\\"').replace(/\n/g, ' ');
    const parts = [];
    parts.push('Kamu adalah parser pesanan untuk depot air minum DAMIU.');
    parts.push('Tugas: ekstrak struktur pesanan dari pesan WhatsApp pelanggan.');
    parts.push('');
    parts.push('Daftar produk di depot:');
    if (!products || products.length === 0) {
        parts.push('(belum ada produk terdaftar)');
    } else {
        for (const p of products) {
            const harga = p.hargaJual ?? p.price ?? 0;
            parts.push(`- ${p.name} (Rp ${Math.round(harga)})`);
        }
    }
    parts.push('');
    if (customers && customers.length) {
        parts.push('Daftar pelanggan terdaftar (untuk match sender):');
        for (const cs of customers) parts.push(`- ${cs}`);
        parts.push('');
    }
    let line = `Sender: ${safe(senderName)}`;
    if (senderPhone) line += ` (${senderPhone})`;
    parts.push(line);
    parts.push(`Pesan: "${safe(message)}"`);
    parts.push('');
    parts.push('Output schema (JSON, WAJIB sesuai):');
    parts.push('{');
    parts.push('  "is_order": boolean,             // true kalau pesan adalah pesanan air/galon');
    parts.push('  "type": "JUAL" | "KEMBALI" | "JUAL_BOTOL",');
    parts.push('  "items": [{"product": string, "qty": int}],');
    parts.push('  "urgent": boolean,               // true kalau pelanggan minta cepat');
    parts.push('  "notes": string,                 // catatan tambahan singkat');
    parts.push('  "confidence": number             // 0..1, seberapa yakin parser');
    parts.push('}');
    parts.push('');
    parts.push('Aturan: kalau pesan TIDAK seperti pesanan (sapaan, basa-basi, complaint), set is_order=false dan items=[]. Pakai nama produk PERSIS dari daftar di atas kalau bisa di-match.');
    parts.push('');
    parts.push("PENTING: Output HANYA JSON murni saja — tanpa markdown (```json), tanpa penjelasan, tanpa text apapun di luar JSON. Karakter pertama harus '{' dan karakter terakhir harus '}'.");
    return parts.join('\n');
}

function stripJsonFences(s) {
    s = s.trim();
    if (s.startsWith('```')) {
        const firstNl = s.indexOf('\n');
        if (firstNl > 0) s = s.substring(firstNl + 1);
        if (s.endsWith('```')) s = s.substring(0, s.length - 3);
        return s.trim();
    }
    return s;
}

// ---------------------------------------------------------------------------
// Routes

app.get('/healthz', (_req, res) => {
    res.json({ ok: true, model: MODEL, ts: Date.now() });
});

app.post('/parse-order', async (req, res) => {
    // Auth check
    const auth = req.headers.authorization || '';
    if (auth !== `Bearer ${TOKEN}`) {
        return res.status(401).json({ error: 'unauthorized' });
    }

    const { senderName, senderPhone, message, products, customers } = req.body || {};
    if (!message || typeof message !== 'string') {
        return res.status(400).json({ error: 'message required' });
    }

    const prompt = buildPrompt(senderName, senderPhone, message, products, customers);

    try {
        let resultText = '';
        const startedAt = Date.now();
        const stream = query({
            prompt,
            options: {
                model: MODEL,
                maxTurns: 1,
                allowedTools: [], // no tools — pure single-shot text completion
            },
        });
        for await (const msg of stream) {
            if (msg.type === 'result') {
                // SDK result message has the final assistant text in `result`
                resultText = msg.result || '';
                break;
            }
        }
        if (!resultText) {
            return res.status(502).json({ error: 'empty_response' });
        }

        const cleaned = stripJsonFences(resultText);
        let parsed;
        try {
            parsed = JSON.parse(cleaned);
        } catch (parseErr) {
            console.warn(`[parse-order] JSON parse failed, returning raw text: ${cleaned.slice(0, 200)}`);
            return res.status(502).json({
                error: 'json_parse_failed',
                detail: parseErr.message,
                raw: cleaned.slice(0, 500),
            });
        }

        const took = Date.now() - startedAt;
        console.log(`[parse-order] sender="${senderName}" took=${took}ms is_order=${parsed.is_order} confidence=${parsed.confidence}`);
        res.json(parsed);
    } catch (err) {
        console.error('[parse-order] Claude SDK error:', err);
        res.status(500).json({ error: 'claude_error', detail: err.message || String(err) });
    }
});

app.listen(PORT, () => {
    console.log(`claude-proxy listening on :${PORT} (model=${MODEL})`);
});
