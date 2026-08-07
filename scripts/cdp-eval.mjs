// Evaluate a JS expression in the device WebView over CDP.
// Usage: node scripts/cdp-eval.mjs '<expression>'
const expr = process.argv[2];
if (!expr) {
  console.error('usage: node cdp-eval.mjs <expression>');
  process.exit(1);
}

const targets = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = targets.find(t => t.type === 'page' || t.webSocketDebuggerUrl);
if (!page) {
  console.error('no debuggable page found');
  process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
const done = new Promise((resolve, reject) => {
  ws.onopen = () => {
    ws.send(JSON.stringify({
      id: 1,
      method: 'Runtime.evaluate',
      params: { expression: expr, returnByValue: true, awaitPromise: true },
    }));
  };
  ws.onmessage = e => {
    const msg = JSON.parse(e.data);
    if (msg.id === 1) resolve(msg.result);
  };
  ws.onerror = reject;
  setTimeout(() => reject(new Error('timeout')), 15000);
});

try {
  const result = await done;
  if (result.exceptionDetails) {
    console.error('EXCEPTION:', JSON.stringify(result.exceptionDetails, null, 2));
  } else {
    console.log(JSON.stringify(result.result?.value ?? result.result, null, 2));
  }
} finally {
  ws.close();
}
